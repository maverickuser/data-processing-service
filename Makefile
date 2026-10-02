# Local command contract. See AGENTS.md. Each target must do what its comment says.
MVN := ./mvnw --batch-mode --no-transfer-progress

.PHONY: build test-unit

## build: compile, run all tests, and package the Lambda deployment artifact (target/data-processing-service-lambda.zip)
build:
	$(MVN) verify

## test-unit: run unit tests only (*Test); no Spring context, Docker, or network
test-unit:
	$(MVN) test
