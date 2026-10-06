# Six functions from one package (LLD section 23.1). Each has its own IAM role holding only the
# AWS actions its code calls, and logs in to PostgreSQL only as its own database role.

locals {
  handler_package = "com.bondplatform.dataprocessing.lambda"

  functions = {
    read-api = {
      handler     = "ApiGatewayHandler"
      timeout     = 29
      reserved    = var.reserved_concurrency.read_api
      db_role     = "processing_reader"
      environment = { API_READ_ROUTES = "true" }
    }
    submission-api = {
      handler     = "ApiGatewayHandler"
      timeout     = 29
      reserved    = var.reserved_concurrency.submission_api
      db_role     = "processing_submission"
      environment = { API_SUBMISSION_ROUTES = "true" }
    }
    # Its concurrency is capped by the event source mapping instead.
    worker = {
      handler     = "ProcessingQueueHandler"
      timeout     = 900
      reserved    = -1
      db_role     = "processing_worker"
      environment = {}
    }
    outbox-sweeper = {
      handler     = "OutboxSweeperHandler"
      timeout     = 60
      reserved    = 1
      db_role     = "processing_sweeper"
      environment = {}
    }
    retention = {
      handler     = "RetentionHandler"
      timeout     = 300
      reserved    = 1
      db_role     = "processing_retention"
      environment = {}
    }
    # Logs in as the master user from the RDS-managed secret, never with an IAM token.
    migration = {
      handler  = "MigrationHandler"
      timeout  = 300
      reserved = 1
      db_role  = null
      environment = {
        DATABASE_IAM_AUTHENTICATION = "false"
        DATABASE_MASTER_SECRET_ARN  = local.persistent.database_master_secret_arn
      }
    }
  }

  # Every function starts the same application context, so each needs these to start.
  common_environment = {
    DATABASE_URL                = "jdbc:postgresql://${local.persistent.database_address}:${local.persistent.database_port}/${local.persistent.database_name}?sslmode=verify-full&sslrootcert=/var/task/rds/ap-south-1-bundle.pem"
    DATABASE_IAM_AUTHENTICATION = "true"
    FILE_PROCESSING_QUEUE_URL   = aws_sqs_queue.processing.url
    SECURITY_DETAILS_QUEUE_URL  = local.security_details_queue_url
    SOURCE_BUCKETS              = var.source_bucket
    CANONICAL_BUCKET            = local.persistent.canonical_bucket
    EVENT_SOURCE                = var.event_source
    PUBLIC_BASE_URL             = "https://${local.domain}"
    API_READ_ROUTES             = "false"
    API_SUBMISSION_ROUTES       = "false"
  }
}

resource "aws_cloudwatch_log_group" "function" {
  for_each          = local.functions
  name              = "/aws/lambda/${local.name}-${each.key}"
  retention_in_days = 30
}

resource "aws_iam_role" "function" {
  for_each = local.functions
  name     = "${local.name}-${each.key}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

locals {
  # Each function's own grants beyond logs and network interfaces.
  function_grants = {
    read-api = []
    submission-api = [{
      Sid      = "SendJobsToTheProcessingQueue"
      Effect   = "Allow"
      Action   = ["sqs:SendMessage"]
      Resource = [aws_sqs_queue.processing.arn]
    }]
    worker = [
      {
        Sid      = "ConsumeTheProcessingQueue"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
        Resource = [aws_sqs_queue.processing.arn]
      },
      {
        Sid      = "SendSecurityDetailsEvents"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = [local.security_details_queue_arn]
      },
      {
        Sid      = "ReadFetchedFiles"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = ["arn:aws:s3:::${var.source_bucket}/${var.source_key_prefix}*"]
      },
      {
        Sid      = "WriteCanonicalFiles"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = ["${local.persistent.canonical_bucket_arn}/canonical/*"]
      },
    ]
    outbox-sweeper = [{
      Sid      = "DeliverPendingEvents"
      Effect   = "Allow"
      Action   = ["sqs:SendMessage"]
      Resource = [aws_sqs_queue.processing.arn, local.security_details_queue_arn]
    }]
    retention = []
    migration = [{
      Sid      = "ReadTheMasterSecret"
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [local.persistent.database_master_secret_arn]
    }]
  }

  function_policies = {
    for name, function in local.functions : name => {
      Version = "2012-10-17"
      Statement = concat(
        [
          {
            Sid      = "WriteOwnLogs"
            Effect   = "Allow"
            Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
            Resource = ["${aws_cloudwatch_log_group.function[name].arn}:*"]
          },
          # Lambda manages the function's network interfaces in the shared subnets with these;
          # EC2 does not scope them to a resource (the same actions as AWSLambdaVPCAccessExecutionRole).
          {
            Sid      = "ManageVpcNetworkInterfaces"
            Effect   = "Allow"
            Action   = ["ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses"]
            Resource = ["*"]
          },
        ],
        function.db_role == null ? [] : [{
          Sid      = "ConnectAsOwnDatabaseRole"
          Effect   = "Allow"
          Action   = ["rds-db:connect"]
          Resource = ["arn:aws:rds-db:${var.aws_region}:${local.account_id}:dbuser:${local.persistent.database_resource_id}/${function.db_role}"]
        }],
        local.function_grants[name],
      )
    }
  }
}

resource "aws_iam_role_policy" "function" {
  for_each = local.functions
  name     = "least-privilege"
  role     = aws_iam_role.function[each.key].id
  policy   = jsonencode(local.function_policies[each.key])
}

resource "aws_lambda_function" "function" {
  for_each      = local.functions
  function_name = "${local.name}-${each.key}"
  role          = aws_iam_role.function[each.key].arn
  handler       = "${local.handler_package}.${each.value.handler}"
  runtime       = "java25"
  architectures = ["arm64"]
  memory_size   = 1024
  timeout       = each.value.timeout

  s3_bucket        = var.package_bucket
  s3_key           = "releases/${var.deployment_commit}/data-processing-service-lambda.zip"
  source_code_hash = var.package_sha256

  # A version is published on every code or setting change; SnapStart snapshots it then.
  publish = true
  snap_start {
    apply_on = "PublishedVersions"
  }

  reserved_concurrent_executions = each.value.reserved

  vpc_config {
    subnet_ids         = module.network.private_subnet_ids
    security_group_ids = [module.network.lambda_security_group_id]
  }

  environment {
    variables = merge(
      local.common_environment,
      each.value.db_role == null ? {} : { DATABASE_USERNAME = each.value.db_role },
      each.value.environment,
    )
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.function[each.key].name
  }

  depends_on = [aws_iam_role_policy.function]
}

# Every trigger targets the live alias. Terraform creates it on the first published version; from
# then on the deployment workflow moves it after the migration succeeds (LLD section 23.6).
resource "aws_lambda_alias" "live" {
  for_each         = local.functions
  name             = "live"
  function_name    = aws_lambda_function.function[each.key].function_name
  function_version = aws_lambda_function.function[each.key].version

  lifecycle {
    ignore_changes = [function_version]
  }
}
