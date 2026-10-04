# Agent instructions — data-processing-service

This file follows the open [AGENTS.md](https://github.com/agentsmd/agents.md) convention. It is the entry point for any coding agent or new contributor. It links to detailed documents rather than repeating them.

The repository is at the planning stage. Only claim a package, command, or feature exists after inspecting the checkout; `docs/plans/implementation-status.md` records what has actually been built and verified.

## Purpose

A Java/Spring Boot service on AWS Lambda and API Gateway that turns files fetched by the upstream `data-fetch-service` into validated securities data:

- BSE debt bhavcopy CSV becomes daily market summaries per `(isin, tradeDate, exchangeName)`.
- NSDL per-ISIN JSON files become security details, cash flows, listings, ratings, and collateral assets.
- A trade for a previously unknown ISIN creates an ISIN-only security and emits one CloudEvent asking the fetch service for its details.

It does not download source files, and it does not expose any write operation on its public API.

## Reading order

1. `docs/README.md` — documentation map and which file is authoritative for what.
2. `docs/specs/structured-file-processing-lld.md` — the design. It is the source of truth for behaviour. Sections 17.5 and 20–22 override earlier sections where they differ.
3. `contracts/*.yaml` — the four processing contracts (two stages per dataset). They override the illustrative YAML in LLD section 6.
4. `docs/specs/data-processing-service-openapi.yaml` (submission) and `docs/specs/data-processing-service-read-openapi.yaml` (public reads).
5. `docs/plans/data-processing-service-implementation-plan.md` and `docs/plans/implementation-status.md` — the PR stack and verified progress.
6. `docs/plans/implementation-context-spec.md` — toolchain pins, deployment inputs, and what is still pending.
7. `docs/engineering/java-standards.md`, `docs/engineering/testing-strategy.md`, `docs/engineering/pr-review.md` — how code is written, tested, and reviewed.

Do not resolve a contradiction between documents by inventing behaviour. Name the conflict and ask one focused question.

## Repository map (target layout)

```text
contracts/                      four YAML processing contracts, packaged as classpath resources
docs/                           specs, plans, engineering standards, decision records
infra/bootstrap                 Terraform state bucket
infra/persistent                RDS PostgreSQL, canonical-file S3 bucket (never destroyed by the application workflow)
infra/application               Lambda functions, API Gateway, SQS queues, schedules, alarms, DNS, IAM (disposable)
src/main/java/com/bondplatform/dataprocessing/
  admission/                    POST /v1/event-ingestions, idempotency, admission receipt
  contract/                     contract model, registry, startup validation
  job/                          ingestion requests, processing runs, worker, retry policy
  source/                       manifest loading, S3 reads, checksum and filename checks
  canonical/                    stage 1: csv/, json/, normalization, validation, canonical file writer
  mapping/                      stage 2: canonical record to internal model
  publication/                  atomic writes to securities_data, new-security detection
  outbox/                       outbox table, dispatcher, SQS publishers
  pipeline/                     dataset handlers joining source, both stages, and publication for a job
  review/                       public read APIs
  operations/                   retention cleanup, failing of stuck jobs
  lambda/                       thin Lambda entry points: API, queue, sweeper, retention, migration
  shared/                       value types (Isin, Percent), clock and ID suppliers, problem details
src/main/resources/db/migration Flyway migrations for securities_data and data_processing
src/test/java                   unit tests (*Test) and integration tests (*IT)
```

The network (VPC, subnets, NAT gateway, endpoints, Lambda security groups) is not in this repository. It is owned by [cloud-platform-network](https://github.com/maverickuser/cloud-platform-network); this service's deployment calls that repository's reusable workflow and its Terraform reads the network state. Never define network resources here.

Each feature package has `domain` (pure Java, no framework imports), `application` (use cases and ports), and `adapter` (web, persistence, AWS) sub-packages. Dependencies point inward: adapter → application → domain. ArchUnit tests enforce this.

## Local command contract

A documented command must never be a placeholder that returns success. Commands are added by the PRs of stack A; `Implemented` shows what exists in this checkout. All need JDK 25 on `PATH` or in `JAVA_HOME`.

| Command | Purpose | Implemented |
|---|---|---|
| `make compile` | Compile main and test sources; Error Prone and NullAway findings fail it | yes |
| `make package` | Build the Lambda artifact without running tests (CI runs them in earlier stages) | yes |
| `make fmt` | Format Java with google-java-format (Spotless) | yes |
| `make lint` | Checkstyle, Error Prone with NullAway, ArchUnit rules | yes |
| `make test-unit` | Unit tests only, no Docker, with a unit-only coverage report | yes |
| `make coverage-check` | Fail unless unit line coverage is strictly greater than 95% | yes |
| `make test-integration` | Integration tests only (`*IT`). Database tests use Testcontainers PostgreSQL 16 and are skipped on a machine without Docker; CI requires Docker | yes |
| `make build` | Compile, run all tests, and package the Lambda artifact `target/data-processing-service-lambda.zip` | yes |
| `make check-contracts` | Validate the four YAML contracts and both OpenAPI documents (YAML and JSON equivalent) | yes |
| `make check-docs` | Validate documentation links | yes |

Unit tests need no Docker and no AWS credentials. Database integration tests need Docker; without it they are skipped locally and still run in CI. Nothing on a pull request uses real AWS.

## Implementation invariants

These come from the LLD. Breaking any of them is a blocking review finding.

- **Two stages.** Stage 1 turns source data into validated canonical records using the source contract. Stage 2 maps canonical records to the internal model using the mapping contract. Stage 2 never parses or normalizes.
- **Exact numbers.** `BigDecimal` and `BigInteger` only. No `float` or `double` anywhere in parsing, storage, or serialization. No rounding.
- **CSV is all-or-nothing per file for publication.** No daily market summary changes until the whole file has been checked; accepted rows are upserted in one transaction.
- **JSON is field-level.** A bad field is rejected alone. Missing, `null`, blank, `-`, and `N.A.` never erase an existing value. Collections are append-only.
- **Admission is durable before `202`.** The request, receipt, pinned contract versions, and dispatch intent are committed together.
- **Lambda-agnostic core.** Only the `lambda` package and adapters may import Lambda or API Gateway types. Use cases run and are tested without them. No background threads or polling loops: work happens inside an invocation.
- **One database connection per execution environment.** Connection pool size is 1; concurrency limits are set in Terraform (LLD 23.5).
- **Outbox only.** Never send to SQS inside or before a database transaction. A new-security event is written in the same transaction that inserts the security.
- **Ordering.** Jobs for one trade date, and jobs for one ISIN, run one at a time in acceptance order, including retries.
- **Canonical storage.** The full canonical output of each run goes to the service's S3 bucket as JSON Lines. Only rejected records are stored in PostgreSQL.
- **Public responses never contain** S3 buckets, keys, version IDs, presigned URLs, or upstream source URLs.
- **Contracts are data.** YAML references a fixed set of Java rules by name. No expressions, no code loading. Unknown rule names fail startup.

## Code expectations

Readable code is the first requirement. See `docs/engineering/java-standards.md` for the full rules. In short:

- Class names are nouns that say what the thing is (`CsvCanonicalizer`, `DuplicateIsinResolver`); method names are verbs that say what happens (`canonicalize`, `resolveWinners`). No `Manager`, `Helper`, `Util`, `Processor`, or `Data` suffixes that hide the responsibility.
- Records for values, constructor injection, no field injection, no static mutable state.
- Every public type and method has a short Javadoc stating its contract, not its implementation.
- One reason to change per class. Prefer a new small class over a boolean flag parameter.

## Tests and review

Every behavioural change arrives with unit tests and, where it crosses a boundary, an integration test. The required cases are listed by ID in `docs/engineering/testing-strategy.md`; a PR states which IDs it adds. Unit line coverage must be strictly greater than 95% across authored production code. Do not exclude difficult code or mix integration coverage into this number.

Review uses separate implementer and reviewer agent passes, as described in `docs/engineering/pr-review.md`. Never claim a human approved something that an agent reviewed.

Before editing: check the current branch, the worktree state, and `implementation-status.md`. Preserve unrelated changes. Report what ran, what passed, and what could not run.

## Stacked PR workflow

Work is about 48 small PRs in nine stacks (the exact number moves as PRs are split or folded; the implementation plan and status file are current), managed with GitHub Stacked PRs. Install the extension once with `gh extension install github/gh-stack` (and optionally its agent skill with `gh skill install github/gh-stack`).

- Start a stack from an up-to-date `main` with `gh stack init <branch>`; add each next PR with `gh stack add <branch>`; open and link the PRs with `gh stack submit`.
- Branch names are `<stack letter>/<PR number>-<slug>` exactly as listed in the implementation plan.
- One plan PR per branch, about 400 changed lines of production code at most. Split rather than grow.
- After changing a lower layer run `gh stack rebase` then `gh stack push`. After a merge run `gh stack sync`. Merge bottom-up with `gh stack merge`.
- Rerun every gate on a rewritten head. Finish a stack before starting the next.

## CI and CD

GitHub Actions only. `ci.yml` runs on every pull request, whatever its base branch, without AWS credentials, as staged jobs: compile, then static analysis, unit tests and coverage, and contracts and documentation; integration tests after unit tests; package last. The `ci passed` job succeeds only when every stage did and is the check required on `main`. Deployment (`deploy.yml`), smoke tests (`smoke.yml`), and application teardown (`destroy-application.yml`) run from `main` and assume an AWS role through OIDC using the `AWS_ROLE_TO_ASSUME` secret. No PR before plan PR 47 applies Terraform or touches real AWS.

## Keeping guidance current

When behaviour changes, update the LLD, the affected contract or OpenAPI file, the test-case list, and the status file in the same PR. Record substantial new decisions in `docs/decisions/`. Keep this file short.
