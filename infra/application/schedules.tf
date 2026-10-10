# EventBridge rules send the scheduled-event shape the handlers read. Each function's resource
# policy admits only its own rule, on the live alias.

locals {
  schedules = {
    outbox-sweeper = { expression = "rate(1 minute)", retries = 0 }
    # 03:00 in India.
    retention = { expression = "cron(30 21 * * ? *)", retries = 2 }
  }
}

resource "aws_cloudwatch_event_rule" "schedule" {
  for_each            = local.schedules
  name                = "${local.name}-${each.key}"
  schedule_expression = each.value.expression
}

resource "aws_cloudwatch_event_target" "schedule" {
  for_each = var.enable_live_triggers ? local.schedules : {}
  rule     = aws_cloudwatch_event_rule.schedule[each.key].name
  arn      = aws_lambda_alias.live[each.key].arn

  retry_policy {
    maximum_retry_attempts       = each.value.retries
    maximum_event_age_in_seconds = 3600
  }
}

# EventBridge retries are set above; Lambda's own asynchronous retries are off, so a failed
# run is retried only as each schedule says.
resource "aws_lambda_function_event_invoke_config" "schedule" {
  for_each                     = local.schedules
  function_name                = aws_lambda_function.function[each.key].function_name
  qualifier                    = aws_lambda_alias.live[each.key].name
  maximum_retry_attempts       = 0
  maximum_event_age_in_seconds = 3600
}

resource "aws_lambda_permission" "schedule" {
  for_each      = var.enable_live_triggers ? local.schedules : {}
  statement_id  = "AllowScheduleInvoke"
  action        = "lambda:InvokeFunction"
  principal     = "events.amazonaws.com"
  function_name = aws_lambda_function.function[each.key].function_name
  qualifier     = aws_lambda_alias.live[each.key].name
  source_arn    = aws_cloudwatch_event_rule.schedule[each.key].arn
}
