# Local command contract. See AGENTS.md. Each target must do what its comment says.
MVN := ./mvnw --batch-mode --no-transfer-progress
COVERAGE_REPORT := target/site/jacoco/jacoco.csv
TERRAFORM ?= terraform
# Every Terraform directory, each checked on its own with its committed provider lock file
INFRA_DIRS := infra/modules/network infra/bootstrap infra/persistent infra/application

.PHONY: compile package check-package fmt lint build test-unit coverage-check test-integration check-contracts check-docs check-infra

## compile: compile main and test sources; Error Prone and NullAway findings fail it
compile:
	$(MVN) test-compile

## fmt: format Java sources with google-java-format
fmt:
	$(MVN) spotless:apply

## lint: formatting check, Checkstyle, Error Prone with NullAway (in compilation), architecture rules
lint:
	$(MVN) spotless:check checkstyle:check test-compile surefire:test -Dtest=ArchitectureTest

## build: compile, run all tests, and package the Lambda deployment artifact (target/data-processing-service-lambda.zip)
build:
	$(MVN) verify
	scripts/check_lambda_package.sh

## package: build the Lambda deployment artifact without running tests. For the CI package stage only,
## which runs after the test stages; a release must use the artifact that CI built, not a local one
package:
	$(MVN) package -DskipUnitTests=true
	scripts/check_lambda_package.sh

## check-package: unpack the Lambda deployment artifact and load each function's handler class from it
check-package:
	scripts/check_lambda_package.sh

## test-unit: run unit tests only (*Test) and write the unit coverage report; no Spring context, Docker, or network
test-unit:
	$(MVN) test

## coverage-check: fail unless unit line coverage is strictly greater than 95%
coverage-check: test-unit
	python3 scripts/test_check_coverage.py
	python3 scripts/check_coverage.py $(COVERAGE_REPORT)

## test-integration: run integration tests only (*IT); needs Docker once tests use Testcontainers
test-integration:
	$(MVN) verify -DskipUnitTests=true -Dassembly.skipAssembly=true

## check-contracts: the four processing contracts are present and well formed; each OpenAPI document is equal in YAML and JSON
check-contracts:
	python3 scripts/test_check_contracts.py
	ruby scripts/check_contracts.rb

## check-docs: every relative Markdown link (inline or reference-style) resolves
check-docs:
	python3 scripts/test_check_docs.py
	python3 scripts/check_docs.py

## check-infra: Terraform formatting, then validate and the mocked `terraform test` suites in every infra directory; no AWS credentials
check-infra:
	$(TERRAFORM) fmt -check -recursive infra
	set -e; for dir in $(INFRA_DIRS); do \
	  $(TERRAFORM) -chdir=$$dir init -backend=false -input=false -lockfile=readonly; \
	  $(TERRAFORM) -chdir=$$dir validate; \
	  $(TERRAFORM) -chdir=$$dir test; \
	done
