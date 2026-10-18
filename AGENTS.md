# Agent instructions — data-processing-service

This file follows the open [AGENTS.md](https://github.com/agentsmd/agents.md) convention. It is the entry point for any coding agent or new contributor. It links to detailed documents rather than repeating them.

Every PR of the implementation plan is merged and the service is deployed at `https://processing.kagent.app`. Work now arrives as one PR per change on `main`; each behaviour change is backed by a decision record in `docs/decisions/`. Only claim a package, command, or feature exists after inspecting the checkout; `docs/plans/implementation-status.md` records what has actually been built and verified.

## Purpose

A Java/Spring Boot service on AWS Lambda and API Gateway that turns files fetched by the upstream `data-fetch-service` into validated securities data:

- BSE debt bhavcopy CSV becomes daily market summaries per `(isin, tradeDate, exchangeName)`.
- NSDL per-ISIN JSON files become security details, cash flows, listings, ratings, and collateral assets.
- The bhavcopy also supplies each security's coupon rate, which takes priority over NSDL's.
- A trade for an unknown ISIN creates an ISIN-only security and emits a CloudEvent asking the fetch service for its details. A traded security still missing a detail is asked for again, at most 3 requests in all, at least a day apart.
- Public read APIs serve securities, daily market summaries, job status, and data-completeness metrics.

It does not download source files, and it does not expose any write operation on its public API.

## Reading order

1. `docs/README.md` — documentation map and which file is authoritative for what.
2. `docs/specs/structured-file-processing-lld.md` — the design. It is the source of truth for behaviour, and its sections agree with each other; a contradiction is a defect to fix, not resolved by section order. `docs/decisions/` explains each change made after the plan; every record also updates the LLD.
3. `contracts/*.yaml` — the four processing contracts (two stages per dataset). They override the illustrative YAML in LLD section 6.
4. `docs/specs/data-processing-service-openapi.yaml` (submission) and `docs/specs/data-processing-service-read-openapi.yaml` (public reads).
5. `docs/plans/implementation-status.md` — verified progress. `docs/plans/active/` holds the plan of any change that spans several PRs; `docs/plans/completed/` holds finished plans, including the original build plan; `docs/plans/tech-debt.md` lists known gaps.
6. `docs/plans/implementation-context-spec.md` — toolchain pins, deployment inputs, and what is still pending.
7. `docs/engineering/java-standards.md`, `docs/engineering/testing-strategy.md`, `docs/engineering/pr-review.md` — how code is written, tested, and reviewed.

Do not resolve a contradiction between documents by inventing behaviour. Name the conflict and ask one focused question.

## Repository map

```text
.github/workflows/              ci, package, deploy, destroy-application, smoke
contracts/                      four YAML processing contracts, packaged as classpath resources
docs/                           specs, plans, engineering standards, decision records
scripts/                        contract, docs, coverage, and package checks; release and smoke scripts, with tests
infra/bootstrap                 Lambda package bucket (the deploy workflow creates the state bucket)
infra/modules/network           read-only view of the shared network state for this service
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

The network (VPC, subnets, VPC endpoints, Lambda security groups; no NAT gateway, decision 0001) is not in this repository. It is owned by [cloud-platform-network](https://github.com/maverickuser/cloud-platform-network); this service's deployment calls that repository's reusable workflow and its Terraform reads the network state. Never define network resources here.

Each feature package has `domain` (pure Java, no framework imports), `application` (use cases and ports), and `adapter` (web, persistence, AWS) sub-packages. Dependencies point inward: adapter → application → domain. ArchUnit tests enforce this.

## Local command contract

A documented command must never be a placeholder that returns success. All need JDK 25 on `PATH` or in `JAVA_HOME`.

| Command | Purpose |
|---|---|
| `make compile` | Compile main and test sources; Error Prone and NullAway findings fail it |
| `make package` | Build the Lambda artifact without running tests (CI runs them in earlier stages) |
| `make fmt` | Format Java with google-java-format (Spotless) |
| `make lint` | Checkstyle, Error Prone with NullAway, ArchUnit rules |
| `make test-unit` | Unit tests only, no Docker, with a unit-only coverage report |
| `make coverage-check` | Fail unless unit line coverage is strictly greater than 95% |
| `make test-integration` | Integration tests only (`*IT`). Database tests use Testcontainers PostgreSQL 16 and are skipped on a machine without Docker; CI requires Docker |
| `make build` | Compile, run all tests, and package the Lambda artifact `target/data-processing-service-lambda.zip` |
| `make check-contracts` | Validate the four YAML contracts and both OpenAPI documents (YAML and JSON equivalent) |
| `make check-docs` | Check documentation against the checkout: relative links, paths and the repository map in this file, `make` targets named in any document, and the decision-record index |
| `make check-infra` | Terraform `fmt`, `validate`, and mocked `terraform test` in every `infra/` directory; needs Terraform 1.16.4 (`TERRAFORM=/path/to/terraform`) but no AWS credentials |

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
- **Migrations are additive.** The previous release keeps running against the new schema until the migration succeeds, so a migration never drops, renames, or narrows anything that release uses. A removal takes two releases (decision 0001).
- **Applied migrations are frozen.** Never edit a merged `V*__*.sql` file, not even a comment: Flyway's checksum check fails and the deploy stops (#91). Add a new migration instead.

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

## PR workflow

The planned build (nine stacks, plan PRs 00–48) is complete. New work is one PR per change, branched from an up-to-date `main`:

- Branch names are `feat/<slug>` for a behaviour change, `fix/<slug>` for a correction, and `docs/<slug>` for documentation only.
- A behaviour change adds a decision record in `docs/decisions/` and updates the LLD, the affected contracts and OpenAPI files, the test-case list, and the status file in the same PR. Any contract change bumps the contract version. A job admitted before the release stays pinned to the old version and fails after it, so the PR says when to deploy (no job of that dataset queued) and which jobs to rerun with `force`.
- Keep a PR to about 400 changed lines of production code. If a change needs more, split it into a stack with `gh stack` (`gh extension install github/gh-stack`): `gh stack init`, `gh stack add`, `gh stack submit`; after changing a lower layer run `gh stack rebase` then `gh stack push`; merge bottom-up with `gh stack merge`.
- Rerun every gate on a rewritten head.

## CI and CD

GitHub Actions only. `ci.yml` runs on every pull request, whatever its base branch, without AWS credentials, as staged jobs: compile, then static analysis, unit tests and coverage, and contracts and documentation; integration tests after unit tests; package last. The `ci passed` job succeeds only when every stage did and is the check required on `main`. **Every merge to `main` deploys to production** (`deploy.yml`, also runnable by hand), so a PR must be safe to release as it stands. Deployment and the manual application teardown (`destroy-application.yml`) assume an AWS role through OIDC using the `AWS_ROLE_TO_ASSUME` secret. Teardown removes only `infra/application`; it never removes the database or any S3 bucket. It does delete the SQS queues (dead-letter messages included) and the function log groups. Smoke tests (`smoke.yml`) run after each successful deployment, send only unsigned requests, and need no AWS credentials. Pull requests never touch real AWS.

## Context management

The repository, not the conversation, holds durable state. When to compact or clear, and what a summary must keep, is in `docs/engineering/context-management.md`. After any compact or purge, re-read this file and the status file, and check the branch and `git status` before editing.

## Keeping guidance current

When behaviour changes, update the LLD, the affected contract or OpenAPI file, the test-case list, and the status file in the same PR. Record substantial new decisions in `docs/decisions/` and list them in its README. Add a known gap you leave unfixed to `docs/plans/tech-debt.md`, and remove it in the PR that fixes it. When a package or `make` target is added, renamed, or removed, update this file in the same PR; `make check-docs` fails until you do. Keep this file short. `CLAUDE.md` only imports this file, so Claude Code and other agents read the same instructions.
