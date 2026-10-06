# Read by data-fetch-service from this root's state (see its infra/README.md).

output "vpc_id" {
  value = module.network.vpc_id
}

output "processor_api_endpoint" {
  value = "https://${local.domain}/v1/event-ingestions"
}

output "processor_submission_route_arn" {
  description = "The fetch delivery role's only execute-api:Invoke grant."
  value       = "${aws_apigatewayv2_api.http.execution_arn}/${aws_apigatewayv2_stage.default.name}/POST/v1/event-ingestions"
}

output "source_reader_role_arns" {
  description = "For the fetch service's processor_reader_role_arns: the only role that reads its artifacts."
  value       = [aws_iam_role.function["worker"].arn]
}

output "security_details_sender_role_arns" {
  description = "For the fetch service's external_producer_role_arns: the roles that send to its ingress queue."
  value       = [aws_iam_role.function["worker"].arn, aws_iam_role.function["outbox-sweeper"].arn]
}

# Used by the deployment workflow.

output "function_names" {
  value = { for name, function in aws_lambda_function.function : name => function.function_name }
}

output "published_versions" {
  description = "The version each function published in this apply; the workflow moves the live alias to them."
  value       = { for name, function in aws_lambda_function.function : name => function.version }
}

output "alias_name" {
  value = "live"
}

output "file_processing_queue_url" {
  value = aws_sqs_queue.processing.url
}
