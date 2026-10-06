# One HTTP API at processing.kagent.app (LLD section 23.2). The read routes are public and go to
# the read function; the submission route needs SigV4 (AWS_IAM) and goes to the submission
# function. The default execute-api hostname is off, so the custom domain is the only way in.

locals {
  read_routes = toset([
    "GET /v1/processing-jobs/{jobId}",
    "GET /v1/processing-jobs/{jobId}/errors",
    "GET /v1/securities/{isin}",
    "GET /v1/securities/{isin}/daily-market-summaries",
    "GET /v1/daily-market-summaries",
  ])
  submission_route = "POST /v1/event-ingestions"
  api_functions    = toset(["read-api", "submission-api"])
}

resource "aws_apigatewayv2_api" "http" {
  name                         = local.name
  protocol_type                = "HTTP"
  disable_execute_api_endpoint = true
}

resource "aws_apigatewayv2_integration" "function" {
  for_each               = local.api_functions
  api_id                 = aws_apigatewayv2_api.http.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_alias.live[each.key].invoke_arn
  payload_format_version = "2.0"
  timeout_milliseconds   = 29000
}

resource "aws_apigatewayv2_route" "read" {
  for_each           = local.read_routes
  api_id             = aws_apigatewayv2_api.http.id
  route_key          = each.key
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.function["read-api"].id}"
}

resource "aws_apigatewayv2_route" "submission" {
  api_id             = aws_apigatewayv2_api.http.id
  route_key          = local.submission_route
  authorization_type = "AWS_IAM"
  target             = "integrations/${aws_apigatewayv2_integration.function["submission-api"].id}"
}

resource "aws_cloudwatch_log_group" "api_access" {
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = 30
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.http.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_rate_limit  = var.read_throttle.rate_limit
    throttling_burst_limit = var.read_throttle.burst_limit
  }

  route_settings {
    route_key              = local.submission_route
    throttling_rate_limit  = var.submission_throttle.rate_limit
    throttling_burst_limit = var.submission_throttle.burst_limit
  }

  # No query string, headers, or body: only the route, status, and timing.
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_access.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      requestTime        = "$context.requestTime"
      routeKey           = "$context.routeKey"
      status             = "$context.status"
      responseLatency    = "$context.responseLatency"
      integrationStatus  = "$context.integrationStatus"
      integrationLatency = "$context.integrationLatency"
      callerArn          = "$context.identity.userArn"
      sourceIp           = "$context.identity.sourceIp"
    })
  }

  depends_on = [aws_apigatewayv2_route.read, aws_apigatewayv2_route.submission]
}

locals {
  # Each API function may be invoked only by this API, for its own method and paths.
  api_source_arns = {
    read-api       = "${aws_apigatewayv2_api.http.execution_arn}/*/GET/v1/*"
    submission-api = "${aws_apigatewayv2_api.http.execution_arn}/*/POST/v1/event-ingestions"
  }
}

resource "aws_lambda_permission" "api" {
  for_each      = local.api_functions
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  principal     = "apigateway.amazonaws.com"
  function_name = aws_lambda_function.function[each.key].function_name
  qualifier     = aws_lambda_alias.live[each.key].name
  source_arn    = local.api_source_arns[each.key]
}

resource "aws_acm_certificate" "api" {
  domain_name       = local.domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = {
    for option in aws_acm_certificate.api.domain_validation_options : option.domain_name => option
  }
  zone_id         = var.hosted_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

resource "aws_apigatewayv2_domain_name" "api" {
  domain_name = local.domain

  domain_name_configuration {
    certificate_arn = aws_acm_certificate_validation.api.certificate_arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
}

resource "aws_apigatewayv2_api_mapping" "api" {
  api_id      = aws_apigatewayv2_api.http.id
  domain_name = aws_apigatewayv2_domain_name.api.id
  stage       = aws_apigatewayv2_stage.default.id
}

resource "aws_route53_record" "api" {
  zone_id = var.hosted_zone_id
  name    = local.domain
  type    = "A"

  alias {
    name                   = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].target_domain_name
    zone_id                = aws_apigatewayv2_domain_name.api.domain_name_configuration[0].hosted_zone_id
    evaluate_target_health = false
  }
}
