# The FIFO processing queue (LLD section 23.3): one message group per ordering group, a
# 16-minute visibility timeout (longer than the worker's 15 minutes), and three receives before
# the dead-letter queue.

resource "aws_sqs_queue" "processing_dlq" {
  name                      = "${local.name}-file-processing-dlq.fifo"
  fifo_queue                = true
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "processing" {
  name                        = "${local.name}-file-processing.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"
  visibility_timeout_seconds  = 960
  message_retention_seconds   = 1209600
  sqs_managed_sse_enabled     = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.processing_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "processing_dlq" {
  queue_url = aws_sqs_queue.processing_dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.processing.arn]
  })
}

locals {
  # Only the submission function and the sweeper may send jobs; nothing may use plain HTTP.
  processing_queue_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.processing.arn
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "OnlyAdmissionAndSweeperSend"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.processing.arn
        Condition = {
          ArnNotEquals = {
            "aws:PrincipalArn" = [aws_iam_role.function["submission-api"].arn, aws_iam_role.function["outbox-sweeper"].arn]
          }
        }
      },
    ]
  }
}

resource "aws_sqs_queue_policy" "processing" {
  queue_url = aws_sqs_queue.processing.id
  policy    = jsonencode(local.processing_queue_policy)
}

resource "aws_lambda_event_source_mapping" "worker" {
  event_source_arn        = aws_sqs_queue.processing.arn
  function_name           = aws_lambda_alias.live["worker"].arn
  batch_size              = 1
  function_response_types = ["ReportBatchItemFailures"]

  scaling_config {
    maximum_concurrency = 10
  }
}
