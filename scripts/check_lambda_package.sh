#!/usr/bin/env bash
# Checks the Lambda deployment artifact (LLD section 23.1): unpacks it as Lambda does, with classes
# at the root and dependencies under lib/, and loads each function's handler class from it alone.
set -euo pipefail

artifact="${1:-target/data-processing-service-lambda.zip}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

unzip -q "$artifact" -d "$work/task"
java -cp "$work/task:$work/task/lib/*" "$(dirname "$0")/LoadHandlers.java" \
  com.bondplatform.dataprocessing.lambda.ApiGatewayHandler \
  com.bondplatform.dataprocessing.lambda.ProcessingQueueHandler \
  com.bondplatform.dataprocessing.lambda.OutboxSweeperHandler \
  com.bondplatform.dataprocessing.lambda.RetentionHandler \
  com.bondplatform.dataprocessing.lambda.MigrationHandler
