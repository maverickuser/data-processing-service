# Mocked: no AWS credentials, no real network or persistent state. Plans only.
mock_provider "aws" {
  override_during = plan

  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:ap-south-1:123456789012:log-group:mock" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }
  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:ap-south-1:123456789012:data-processing-service-file-processing.fifo"
      url = "https://sqs.ap-south-1.amazonaws.com/123456789012/data-processing-service-file-processing.fifo"
    }
  }
  mock_resource "aws_lambda_function" {
    defaults = { version = "1" }
  }
  mock_resource "aws_lambda_alias" {
    defaults = {
      arn        = "arn:aws:lambda:ap-south-1:123456789012:function:mock:live"
      invoke_arn = "arn:aws:apigateway:ap-south-1:lambda:path/2015-03-31/functions/arn:aws:lambda:ap-south-1:123456789012:function:mock:live/invocations"
    }
  }
  mock_resource "aws_apigatewayv2_api" {
    defaults = {
      id            = "abc123"
      execution_arn = "arn:aws:execute-api:ap-south-1:123456789012:abc123"
    }
  }
  mock_resource "aws_apigatewayv2_integration" {
    defaults = { id = "integ1" }
  }
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:ap-south-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
      domain_validation_options = [{
        domain_name           = "processing.kagent.app"
        resource_record_name  = "_validation.processing.kagent.app"
        resource_record_type  = "CNAME"
        resource_record_value = "_validation.acm-validations.aws"
      }]
    }
  }
  mock_resource "aws_cloudwatch_event_rule" {
    defaults = { arn = "arn:aws:events:ap-south-1:123456789012:rule/mock" }
  }
}

override_resource {
  target          = aws_iam_role.function["submission-api"]
  override_during = plan
  values          = { arn = "arn:aws:iam::123456789012:role/data-processing-service-submission-api" }
}

override_resource {
  target          = aws_iam_role.function["worker"]
  override_during = plan
  values          = { arn = "arn:aws:iam::123456789012:role/data-processing-service-worker" }
}

override_resource {
  target          = aws_iam_role.function["outbox-sweeper"]
  override_during = plan
  values          = { arn = "arn:aws:iam::123456789012:role/data-processing-service-outbox-sweeper" }
}

override_module {
  target = module.network
  outputs = {
    vpc_id                   = "vpc-0123456789abcdef0"
    vpc_cidr                 = "10.20.0.0/16"
    private_subnet_ids       = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
    lambda_security_group_id = "sg-0123456789abcdef0"
  }
}

override_data {
  target = data.terraform_remote_state.persistent
  values = {
    outputs = {
      database_address           = "data-processing-service.abc.ap-south-1.rds.amazonaws.com"
      database_port              = 5432
      database_name              = "data_processing"
      database_resource_id       = "db-ABCDEFGHIJKLMNOP"
      database_master_secret_arn = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:rds!db-1234-AbCdEf"
      database_security_group_id = "sg-0dddddddddddddddd"
      canonical_bucket           = "data-processing-service-canonical"
      canonical_bucket_arn       = "arn:aws:s3:::data-processing-service-canonical"
    }
  }
}

variables {
  hosted_zone_id    = "Z1234567890"
  deployment_commit = "0123456789abcdef0123456789abcdef01234567"
  package_sha256    = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
}

run "six_snapstart_functions_in_the_shared_private_subnets" {
  command = plan

  assert {
    condition     = keys(aws_lambda_function.function) == ["migration", "outbox-sweeper", "read-api", "retention", "submission-api", "worker"]
    error_message = "There are exactly six functions."
  }

  assert {
    condition = alltrue([for f in aws_lambda_function.function : (
      f.runtime == "java25" && tolist(f.architectures) == tolist(["arm64"]) && f.memory_size == 1024 && f.publish &&
      one(f.snap_start).apply_on == "PublishedVersions" &&
      one(f.vpc_config).subnet_ids == toset(["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]) &&
      one(f.vpc_config).security_group_ids == toset(["sg-0123456789abcdef0"]) &&
      f.s3_bucket == "data-processing-service-artifacts" &&
      f.s3_key == "releases/0123456789abcdef0123456789abcdef01234567/data-processing-service-lambda.zip"
    )])
    error_message = "Every function runs the released package on java25 arm64 with SnapStart in the shared private subnets."
  }

  assert {
    condition = (
      aws_lambda_function.function["read-api"].handler == "com.bondplatform.dataprocessing.lambda.ApiGatewayHandler" &&
      aws_lambda_function.function["submission-api"].handler == "com.bondplatform.dataprocessing.lambda.ApiGatewayHandler" &&
      aws_lambda_function.function["worker"].handler == "com.bondplatform.dataprocessing.lambda.ProcessingQueueHandler" &&
      aws_lambda_function.function["outbox-sweeper"].handler == "com.bondplatform.dataprocessing.lambda.OutboxSweeperHandler" &&
      aws_lambda_function.function["retention"].handler == "com.bondplatform.dataprocessing.lambda.RetentionHandler" &&
      aws_lambda_function.function["migration"].handler == "com.bondplatform.dataprocessing.lambda.MigrationHandler"
    )
    error_message = "Each function has its own handler."
  }

  assert {
    condition = (
      aws_lambda_function.function["read-api"].timeout == 29 && aws_lambda_function.function["read-api"].reserved_concurrent_executions == 10 &&
      aws_lambda_function.function["submission-api"].timeout == 29 && aws_lambda_function.function["submission-api"].reserved_concurrent_executions == 5 &&
      aws_lambda_function.function["worker"].timeout == 900 &&
      aws_lambda_function.function["outbox-sweeper"].timeout == 60 && aws_lambda_function.function["outbox-sweeper"].reserved_concurrent_executions == 1 &&
      aws_lambda_function.function["retention"].timeout == 300 && aws_lambda_function.function["retention"].reserved_concurrent_executions == 1 &&
      aws_lambda_function.function["migration"].timeout == 300 && aws_lambda_function.function["migration"].reserved_concurrent_executions == 1
    )
    error_message = "Timeouts and concurrency follow LLD section 23.1."
  }

  assert {
    condition     = alltrue([for name, alias in aws_lambda_alias.live : alias.name == "live" && alias.function_version == "1"])
    error_message = "Every function has a live alias on its first published version."
  }
}

run "each_function_serves_only_its_routes_and_logs_in_as_its_own_role" {
  command = plan

  assert {
    condition = alltrue([for name, f in aws_lambda_function.function : (
      one(f.environment).variables["API_READ_ROUTES"] == (name == "read-api" ? "true" : "false") &&
      one(f.environment).variables["API_SUBMISSION_ROUTES"] == (name == "submission-api" ? "true" : "false")
    )])
    error_message = "Only the read function serves read routes and only the submission function serves the submission route."
  }

  assert {
    condition = (
      one(aws_lambda_function.function["read-api"].environment).variables["DATABASE_USERNAME"] == "processing_reader" &&
      one(aws_lambda_function.function["submission-api"].environment).variables["DATABASE_USERNAME"] == "processing_submission" &&
      one(aws_lambda_function.function["worker"].environment).variables["DATABASE_USERNAME"] == "processing_worker" &&
      one(aws_lambda_function.function["outbox-sweeper"].environment).variables["DATABASE_USERNAME"] == "processing_sweeper" &&
      one(aws_lambda_function.function["retention"].environment).variables["DATABASE_USERNAME"] == "processing_retention" &&
      !contains(keys(one(aws_lambda_function.function["migration"].environment).variables), "DATABASE_USERNAME")
    )
    error_message = "Each function logs in as its own database role; the migration function as the master user from the secret."
  }

  assert {
    condition = alltrue([for name, f in aws_lambda_function.function : (
      one(f.environment).variables["DATABASE_URL"] == "jdbc:postgresql://data-processing-service.abc.ap-south-1.rds.amazonaws.com:5432/data_processing?sslmode=verify-full&sslrootcert=/var/task/rds/ap-south-1-bundle.pem" &&
      one(f.environment).variables["DATABASE_IAM_AUTHENTICATION"] == (name == "migration" ? "false" : "true") &&
      contains(keys(one(f.environment).variables), "DATABASE_MASTER_SECRET_ARN") == (name == "migration") &&
      !contains(keys(one(f.environment).variables), "DATABASE_PASSWORD")
    )])
    error_message = "Every function verifies the server certificate; only the migration function knows the master secret, and none holds a password."
  }

  assert {
    condition = alltrue([for f in aws_lambda_function.function : (
      one(f.environment).variables["FILE_PROCESSING_QUEUE_URL"] == "https://sqs.ap-south-1.amazonaws.com/123456789012/data-processing-service-file-processing.fifo" &&
      one(f.environment).variables["SECURITY_DETAILS_QUEUE_URL"] == "https://sqs.ap-south-1.amazonaws.com/123456789012/data-fetch-service-ingress" &&
      one(f.environment).variables["SOURCE_BUCKETS"] == "data-fetch-service-artifacts" &&
      one(f.environment).variables["CANONICAL_BUCKET"] == "data-processing-service-canonical" &&
      one(f.environment).variables["EVENT_SOURCE"] == "urn:bond-platform:structured-file-processing:prod" &&
      one(f.environment).variables["PUBLIC_BASE_URL"] == "https://processing.kagent.app"
    )])
    error_message = "Every function gets the settings its application context needs to start."
  }
}

run "each_role_holds_only_the_actions_its_function_uses" {
  command = plan

  assert {
    condition = {
      for name, policy in aws_iam_role_policy.function : name => sort(flatten([for s in jsondecode(policy.policy).Statement : s.Action]))
      } == {
      "read-api"       = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "rds-db:connect"])
      "retention"      = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "rds-db:connect"])
      "submission-api" = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "rds-db:connect", "sqs:SendMessage"])
      "outbox-sweeper" = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "rds-db:connect", "sqs:SendMessage"])
      "worker"         = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "rds-db:connect", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility", "sqs:SendMessage", "s3:GetObject", "s3:GetObjectVersion", "s3:PutObject"])
      "migration"      = sort(["logs:CreateLogStream", "logs:PutLogEvents", "ec2:CreateNetworkInterface", "ec2:DescribeNetworkInterfaces", "ec2:DescribeSubnets", "ec2:DeleteNetworkInterface", "ec2:AssignPrivateIpAddresses", "ec2:UnassignPrivateIpAddresses", "secretsmanager:GetSecretValue"])
    }
    error_message = "A function role holds an action its code does not use, or lacks one it does."
  }

  assert {
    condition = alltrue([for name, policy in aws_iam_role_policy.function : alltrue([
      for s in jsondecode(policy.policy).Statement : s.Effect == "Allow" && (s.Resource != ["*"] || s.Sid == "ManageVpcNetworkInterfaces")
    ])])
    error_message = "Only the network-interface actions, which EC2 cannot scope, may use a wildcard resource."
  }

  assert {
    condition = {
      for name, policy in aws_iam_role_policy.function : name => one([for s in jsondecode(policy.policy).Statement : s.Resource if s.Sid == "ConnectAsOwnDatabaseRole"])
      if name != "migration"
      } == {
      "read-api"       = ["arn:aws:rds-db:ap-south-1:123456789012:dbuser:db-ABCDEFGHIJKLMNOP/processing_reader"]
      "submission-api" = ["arn:aws:rds-db:ap-south-1:123456789012:dbuser:db-ABCDEFGHIJKLMNOP/processing_submission"]
      "worker"         = ["arn:aws:rds-db:ap-south-1:123456789012:dbuser:db-ABCDEFGHIJKLMNOP/processing_worker"]
      "outbox-sweeper" = ["arn:aws:rds-db:ap-south-1:123456789012:dbuser:db-ABCDEFGHIJKLMNOP/processing_sweeper"]
      "retention"      = ["arn:aws:rds-db:ap-south-1:123456789012:dbuser:db-ABCDEFGHIJKLMNOP/processing_retention"]
    }
    error_message = "Each function may connect only as its own database role."
  }

  assert {
    condition = (
      one([for s in jsondecode(aws_iam_role_policy.function["migration"].policy).Statement : s.Resource if s.Sid == "ReadTheMasterSecret"]) == ["arn:aws:secretsmanager:ap-south-1:123456789012:secret:rds!db-1234-AbCdEf"] &&
      one([for s in jsondecode(aws_iam_role_policy.function["submission-api"].policy).Statement : s.Resource if s.Sid == "SendJobsToTheProcessingQueue"]) == ["arn:aws:sqs:ap-south-1:123456789012:data-processing-service-file-processing.fifo"] &&
      one([for s in jsondecode(aws_iam_role_policy.function["worker"].policy).Statement : s.Resource if s.Sid == "SendSecurityDetailsEvents"]) == ["arn:aws:sqs:ap-south-1:123456789012:data-fetch-service-ingress"] &&
      one([for s in jsondecode(aws_iam_role_policy.function["worker"].policy).Statement : s.Resource if s.Sid == "ReadFetchedFiles"]) == ["arn:aws:s3:::data-fetch-service-artifacts/runs/*"] &&
      one([for s in jsondecode(aws_iam_role_policy.function["worker"].policy).Statement : s.Resource if s.Sid == "WriteCanonicalFiles"]) == ["arn:aws:s3:::data-processing-service-canonical/canonical/*"]
    )
    error_message = "Grants name only the secret, queues, and key prefixes each function uses."
  }

  assert {
    condition = alltrue([for name, role in aws_iam_role.function :
      jsondecode(role.assume_role_policy).Statement[0].Principal == { Service = "lambda.amazonaws.com" }
    ])
    error_message = "Only Lambda may assume a function role."
  }
}

run "processing_queue_orders_retries_and_admits_only_its_senders" {
  command = plan

  assert {
    condition = (
      aws_sqs_queue.processing.fifo_queue && !aws_sqs_queue.processing.content_based_deduplication &&
      aws_sqs_queue.processing.visibility_timeout_seconds == 960 &&
      jsondecode(aws_sqs_queue.processing.redrive_policy).maxReceiveCount == 3 &&
      aws_sqs_queue.processing_dlq.fifo_queue &&
      aws_sqs_queue.processing.sqs_managed_sse_enabled && aws_sqs_queue.processing_dlq.sqs_managed_sse_enabled
    )
    error_message = "FIFO with outbox-ID deduplication, a 16-minute visibility timeout, and three receives before the encrypted DLQ."
  }

  assert {
    condition = (
      aws_lambda_event_source_mapping.worker.batch_size == 1 &&
      one(aws_lambda_event_source_mapping.worker.scaling_config).maximum_concurrency == 10 &&
      aws_lambda_event_source_mapping.worker.function_response_types == toset(["ReportBatchItemFailures"])
    )
    error_message = "The worker takes one message at a time, at most ten at once, and reports failures per message."
  }

  assert {
    condition = (
      local.processing_queue_policy.Statement[1].Effect == "Deny" &&
      toset(local.processing_queue_policy.Statement[1].Condition.ArnNotEquals["aws:PrincipalArn"]) == toset([
        "arn:aws:iam::123456789012:role/data-processing-service-submission-api",
        "arn:aws:iam::123456789012:role/data-processing-service-outbox-sweeper",
      ])
    )
    error_message = "Only the submission function and the sweeper may send to the processing queue."
  }
}

run "public_reads_and_signed_submission_at_the_custom_domain" {
  command = plan

  assert {
    condition     = aws_apigatewayv2_api.http.disable_execute_api_endpoint
    error_message = "The default execute-api hostname must be off."
  }

  assert {
    condition = (
      length(aws_apigatewayv2_route.read) == 5 &&
      alltrue([for key, route in aws_apigatewayv2_route.read : startswith(key, "GET /v1/") && route.authorization_type == "NONE"])
    )
    error_message = "The five read routes are GET and have no authorizer."
  }

  assert {
    condition     = aws_apigatewayv2_route.submission.route_key == "POST /v1/event-ingestions" && aws_apigatewayv2_route.submission.authorization_type == "AWS_IAM"
    error_message = "The submission route requires SigV4."
  }

  assert {
    condition = (
      one(aws_apigatewayv2_stage.default.default_route_settings).throttling_rate_limit == 20 &&
      one(aws_apigatewayv2_stage.default.default_route_settings).throttling_burst_limit == 40 &&
      one(aws_apigatewayv2_stage.default.route_settings).route_key == "POST /v1/event-ingestions"
    )
    error_message = "The API is throttled."
  }

  assert {
    condition = (
      aws_lambda_permission.api["read-api"].source_arn == "arn:aws:execute-api:ap-south-1:123456789012:abc123/*/GET/v1/*" &&
      aws_lambda_permission.api["submission-api"].source_arn == "arn:aws:execute-api:ap-south-1:123456789012:abc123/*/POST/v1/event-ingestions" &&
      alltrue([for p in aws_lambda_permission.api : p.qualifier == "live"])
    )
    error_message = "Each API function admits only its own method and paths, on the live alias."
  }

  assert {
    condition     = one(aws_apigatewayv2_domain_name.api.domain_name_configuration).security_policy == "TLS_1_2" && aws_apigatewayv2_domain_name.api.domain_name == "processing.kagent.app"
    error_message = "The custom domain requires TLS 1.2."
  }

  assert {
    condition     = !strcontains(aws_apigatewayv2_stage.default.access_log_settings[0].format, "querystring") && !strcontains(aws_apigatewayv2_stage.default.access_log_settings[0].format, "path")
    error_message = "Access logs record the route, not request values."
  }
}

run "schedules_and_alarms" {
  command = plan

  assert {
    condition = (
      aws_cloudwatch_event_rule.schedule["outbox-sweeper"].schedule_expression == "rate(1 minute)" &&
      aws_cloudwatch_event_rule.schedule["retention"].schedule_expression == "cron(30 21 * * ? *)" &&
      alltrue([for p in aws_lambda_permission.schedule : p.principal == "events.amazonaws.com" && p.qualifier == "live"])
    )
    error_message = "The sweeper runs every minute and retention daily, each invoked only by its rule."
  }

  assert {
    condition = (
      length(aws_cloudwatch_metric_alarm.function_errors) == 6 &&
      aws_cloudwatch_metric_alarm.outbox_backlog.threshold == 600 && aws_cloudwatch_metric_alarm.outbox_backlog.treat_missing_data == "breaching" &&
      aws_cloudwatch_metric_alarm.failed_jobs.dimensions["Outcome"] == "FAILED" && length(aws_cloudwatch_metric_alarm.failed_jobs.dimensions) == 1 &&
      aws_cloudwatch_metric_alarm.stuck_jobs.metric_name == "StuckJobsFailed" &&
      aws_cloudwatch_metric_alarm.dead_letters.threshold == 0
    )
    error_message = "Alarms cover function errors, the DLQ, failed and stuck jobs, the outbox backlog, and API 5xx."
  }

  assert {
    condition     = length(aws_sns_topic_subscription.email) == 0
    error_message = "No one is subscribed until an alert recipient is given."
  }
}

run "outputs_match_what_the_fetch_service_checks" {
  command = plan

  assert {
    condition     = output.processor_api_endpoint == "https://processing.kagent.app/v1/event-ingestions"
    error_message = "The fetch service requires exactly this endpoint."
  }

  assert {
    condition     = can(regex("^arn:aws:execute-api:ap-south-1:[0-9]{12}:[^/]+/[^/]+/POST/v1/event-ingestions$", output.processor_submission_route_arn))
    error_message = "The route ARN must match the fetch service's precondition."
  }

  assert {
    condition     = output.vpc_id == "vpc-0123456789abcdef0" && output.source_reader_role_arns == ["arn:aws:iam::123456789012:role/data-processing-service-worker"]
    error_message = "The fetch service reads the VPC and grants artifact reads to the worker only."
  }
}

run "refuses_a_short_commit" {
  command = plan

  variables {
    deployment_commit = "0123456"
  }

  expect_failures = [var.deployment_commit]
}

run "refuses_a_source_prefix_that_widens_reads" {
  command = plan

  variables {
    source_key_prefix = ""
  }

  expect_failures = [var.source_key_prefix]
}
