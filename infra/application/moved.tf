# Existing installations used singleton addresses before the staged first release was added.
moved {
  from = aws_apigatewayv2_route.submission
  to   = aws_apigatewayv2_route.submission[0]
}

moved {
  from = aws_lambda_event_source_mapping.worker
  to   = aws_lambda_event_source_mapping.worker[0]
}
