# Data Processing Service — Implementation Context Specification

Status: input checklist for implementation. Date: 2026-10-02.

The LLD defines behaviour. This document records the repository, tooling, contracts, fixtures, and deployment facts needed to build it. Each item is `known`, `assumed`, or `pending`.

- Keep one authoritative value per input and link its source.
- Never put secrets, tokens, or production payloads here; record names and locations only.
- Update this file in the PR that changes an input.
- A pending deployment value does not block local implementation; it blocks only the PR that needs it.

## Agreed

| Context | Value | Status |
|---|---|---|
| Repository | `https://github.com/maverickuser/data-processing-service`, default branch `main` | known |
| Language and framework | Java, Spring Boot, one build artifact | known |
| Runtime | AWS Lambda (arm64, 1024 MB, SnapStart) behind API Gateway HTTP API; five functions (LLD section 23) | known (agreed 2026-10-02) |
| Database | RDS PostgreSQL 16, `db.t4g.micro`, 20 GB gp3, single AZ, 7-day backups | known |
| Region and environment | `ap-south-1`, `prod` only; region from GitHub variable `aws_region` | known |
| CI/CD | GitHub Actions; CI on every pull request; CD from `main` | known |
| Stacking | GitHub Stacked PRs with the `gh stack` extension (`github/gh-stack`) | known |
| GitHub to AWS authentication | OIDC role assumption; role ARN in repository secret `AWS_ROLE_TO_ASSUME` | known (same mechanism as data-fetch-service) |
| API hostname | `processing.kagent.app` in the existing shared Route 53 zone `kagent.app` | known |
| Network | VPC `10.20.0.0/16`, two AZs, one NAT gateway, no load balancers | known |
| Submission access | AWS IAM authorization (SigV4) on `POST /v1/event-ingestions`, granted to the fetch service's delivery role | known |
| Review | Implementer agent and separate reviewer agent; no human approval required | known |
| Test gate | Unit line coverage strictly greater than 95%; integration tests on Testcontainers and LocalStack; smoke tests after deployment | known (confirmed 2026-10-02) |

## Toolchain

Versions marked assumed must be checked against the current stable releases and pinned in PR 01. Record the pinned value here in that PR.

| Input | Value | Status |
|---|---|---|
| Java | 25 (LTS); `java.version` 25 in `pom.xml` | known: pinned in PR 01 |
| Spring Boot | 4.1.1 (parent POM) | known: pinned in PR 01 |
| Build | Maven with wrapper (`./mvnw`), wrapped by `make`. A JDK 25 must be on `PATH` or in `JAVA_HOME` | known |
| Lambda artifact | `target/data-processing-service-lambda.zip`: classes at the root, dependencies under `lib/` | known: built in PR 01 |
| Formatting | Spotless Maven plugin 3.10.3 with google-java-format 1.35.0 (1.37.0 fails inside this Spotless release) | known: pinned in PR 02 |
| Static analysis | Checkstyle 14.3.0 (Google checks), Error Prone 2.50.0, NullAway 0.14.2, JSpecify 1.0.1; `-Werror` | known: pinned in PR 02 |
| Architecture tests | ArchUnit 1.5.1 | known: pinned in PR 02 |
| Coverage | JaCoCo 0.8.15 report, gate in `scripts/check_coverage.py` | known: pinned in PR 02 |
| Tests | JUnit 5, AssertJ, Mockito (sparingly), Testcontainers: PostgreSQL 16, ElasticMQ 1.7.1 for SQS, Adobe S3Mock 5.2.3 for S3 (LocalStack images now need an account token) | known (2026-10-03) |
| CSV parsing | Apache Commons CSV | assumed |
| JSON | Jackson 3 (`tools.jackson`, managed by Spring Boot) with `BigDecimal` numbers; strict duplicate detection is added with the JSON reader in PR 30 | known |
| AWS access | AWS SDK for Java v2 (S3, SQS) | assumed |
| Lambda runtime | `java25`, with SnapStart (supported for Java 11 and later) | known: listed in the AWS Lambda runtimes documentation on 2026-10-02; availability in `ap-south-1` is confirmed at first deployment |
| Spring on Lambda adapter | `com.amazonaws.serverless:aws-serverless-java-container-springboot4` 3.0.2 | known: in the build since PR 05; an API Gateway HTTP API event round-trips through it in `ApiGatewayHandlerIT` |
| Database access | Spring `JdbcClient`, Flyway | known (Flyway agreed in LLD 21) |
| Terraform | 1.16.4, `required_version = "~> 1.16.4"` | assumed: same pin as data-fetch-service |
| AWS provider | `hashicorp/aws` 6.61.0 with committed lock file | assumed: same pin as data-fetch-service |

## Contracts and fixtures

| Input | Value | Status |
|---|---|---|
| Submission API | `docs/specs/data-processing-service-openapi.yaml` and `.json` | known |
| Read API | `docs/specs/data-processing-service-read-openapi.yaml` and `.json` | known; not yet run through an OpenAPI validator |
| Processing contracts | `contracts/*.yaml` (four files) | known |
| Table definitions | LLD section 22 | known; not yet run against PostgreSQL |
| Bhavcopy fixture | Sanitised extract of `fgroup01012026.csv` with its golden canonical and mapped outputs | pending: create in PR 23–25 |
| NSDL fixtures | The five sample payloads for `INE831R08076` plus a redemptions file, with golden outputs | known: fetched from the public NSDL endpoints on 2026-10-02 and stored under `src/test/resources/fixtures/nsdl/` using the fetch service's filenames (`{isin}_{job_id}.json`): all six files for `INE831R08076`, plus `INE0O7U07046_coupon-details.json` (a bond with partial redemptions). Golden outputs are created in PR 30–32 |
| Upstream manifest examples | LLD section 19 and the fetch-service LLD section 11 | known |

## AWS and deployment

| Input | Needed by | Status |
|---|---|---|
| OIDC deployment role | PR 47 | known: the same role as data-fetch-service, `arn:aws:iam::055173110395:role/GitHubDeploy`, stored in this repository's secret `AWS_ROLE_TO_ASSUME`. Before PR 47, verify that the role's trust policy allows this repository's OIDC subject and that its permissions cover ECS, ECR, RDS, ALB, ACM, and Route 53 |
| Terraform state bucket name | PR 44 | assumed: `data-processing-service-terraform-state`, created by `infra/bootstrap`. Separate from the network repository's state bucket |
| Owner of the shared network | PR 44, PR 47 | known (2026-10-02): the separate repository [cloud-platform-network](https://github.com/maverickuser/cloud-platform-network). State bucket `cloud-platform-network-terraform-state`, key `network/terraform.tfstate`. Called through its reusable workflow `apply.yml`. This supersedes the earlier decision to keep `infra/network` in this repository |
| `processing` Lambda security group in the network repository | PR 44, PR 47 | pending: needs a pull request there adding `processing` to `lambda_security_groups`, then a version tag |
| Network repository version tag | PR 47 | pending: `v1` is not tagged yet |
| Function environment variables | PR 45, PR 47 | known (2026-10-03): every function shares one application context, so all five need `FILE_PROCESSING_QUEUE_URL` (must end in `.fifo`), `SECURITY_DETAILS_QUEUE_URL`, `SOURCE_BUCKETS` (the fetch service's artifact bucket), and `CANONICAL_BUCKET` (the service's canonical-file bucket) or they fail to start; optional `PUBLIC_BASE_URL`. `AWS_REGION` is set by Lambda |
| Route 53 hosted-zone ID for `kagent.app` | PR 46 | pending |
| Security-details queue ARN, URL, and type (Standard or FIFO) | PR 46, PR 29 configuration | pending: from data-fetch-service Terraform outputs |
| Fetch-service artifact bucket name | PR 46 | pending: `data-fetch-service-artifacts` appears in examples; confirm the deployed name |
| Canonical-file bucket name | PR 45 | assumed: `data-processing-service-canonical` |
| Encryption | PR 45–46 | assumed: SSE-S3, SSE-SQS, RDS default encryption; no customer-managed keys |
| Alert recipient | PR 42, PR 46 | pending |
| Fetch delivery role ARN | PR 46 | pending: from data-fetch-service Terraform outputs; needed for the `execute-api:Invoke` grant |
| Artifact bucket for Lambda packages | PR 44 | assumed: `data-processing-service-artifacts`, created by `infra/bootstrap` |

## Collection order for pending inputs

1. Confirm toolchain versions (PR 01).
2. NSDL sample payloads for fixtures (before stack F).
3. Deployment role, hosted-zone ID, queue and bucket names (before PR 46).
4. Alert recipient (before PR 47).

## Agent operating rules

Read `AGENTS.md`, the LLD, this file, and the status file before editing. Inspect the real branch and CI state. Continue local work with fakes while deployment inputs are pending. Do not guess production values, create AWS resources before PR 47, or claim live readiness without smoke evidence.
