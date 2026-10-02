# Implementation status

Factual record of progress against the [implementation plan](data-processing-service-implementation-plan.md). Update it in every PR.

Rules: a PR link is added only when the PR exists. `State` is one of `not started`, `in progress`, `in review`, `merged`. `Verified checks` lists what actually ran and passed on the merged head. Planned or mocked work is never marked deployed or live-tested.

Last updated: 2026-10-02.

| PR | Stack | Branch | State | PR link | Verified checks | Notes |
|---|---|---|---|---|---|---|
| 00 | — | `docs/implementation-plan` | merged | [#1](https://github.com/maverickuser/data-processing-service/pull/1) | YAML contracts parse; OpenAPI YAML and JSON equivalent; relative links resolve | Documentation baseline and NSDL fixtures |
| 01 | A Foundation | `a/01-build-skeleton` | in review | [#2](https://github.com/maverickuser/data-processing-service/pull/2) | `make build`, `make test-unit` (local, JDK 25.0.4.1). No CI run exists for this layer because `ci.yml` arrives in PR 03; the reviewer agent ran its gates on an export of the branch and they passed | Context-load test `DataProcessingApplicationIT`. Review fix: the Maven wrapper verifies the distribution checksum |
| 02 | A Foundation | `a/02-quality-gates` | in review | [#3](https://github.com/maverickuser/data-processing-service/pull/3) | `make fmt lint build`, `make coverage-check COVERAGE_ALLOW_EMPTY=--allow-empty` on this layer (local). No CI run on this layer, as for PR 01; reviewer agent ran the gates on an export. Seeded format, Checkstyle, NullAway, and Error Prone violations each failed their gate. Five mutations of rule clauses: four detected by the fixture tests, one (array unwrapping) redundant | U-ARCH-01..03. One fixture class per banned thing, each asserted by name in the failure report; a compliant fixture passes every rule. Rules see fields, signatures, and calls, not local variable types. 8 coverage-checker tests |
| 03 | A Foundation | `a/03-ci-workflow` | in review | [#5](https://github.com/maverickuser/data-processing-service/pull/5) | `make lint test-integration check-contracts check-docs` (local). GitHub Actions `quality gates` passes on stacked PRs | 7 contract-check tests, 7 link-check tests. Deviation: the Testcontainers base class moves to PR 10, where a database is first used. Required status checks on `main` are enabled after this stack merges |
| 04 | A Foundation | `a/04-value-types` | in review | [#6](https://github.com/maverickuser/data-processing-service/pull/6) | `make lint build`, `make coverage-check`: 60/60 lines on this layer (local) | U-VAL-01, U-VAL-02; 118 unit tests on this layer. `TradeDate` filename parsing (U-VAL-03) is in PR 22 as planned. `Isin` and `ExchangeName` strip Unicode whitespace only; a no-break space or byte-order mark inside a value is kept (reviewer question V-6, left as is) |
| 05 | A Foundation | `a/05-web-baseline` | in review | [#7](https://github.com/maverickuser/data-processing-service/pull/7) | `make lint build check-contracts check-docs`; `make coverage-check`: 133/138 lines, identical on a clean tree and after a full build (local). 143 unit tests, 11 integration tests | Serialisation part of I-READ-08. `HttpErrorResponsesIT` sends API Gateway events through the Lambda handler and checks 404, 405, 406, 415, 400 (missing parameter, malformed date, malformed JSON), and 500 keep their status and problem shape. The five uncovered lines are the Lambda constructor and Spring start-up in `ApiGatewayHandler`, exercised only by integration tests. Deviation: the validation `ErrorCode` enum moves to PR 24 |
| 06 | B Contracts and schema | `b/06-text-rules` | not started | — | — | |
| 07 | B Contracts and schema | `b/07-numeric-rules` | not started | — | — | |
| 08 | B Contracts and schema | `b/08-date-rule-and-contract-model` | not started | — | — | |
| 09 | B Contracts and schema | `b/09-contract-registry` | not started | — | — | |
| 10 | B Contracts and schema | `b/10-schema-securities` | not started | — | — | |
| 11 | B Contracts and schema | `b/11-schema-processing` | not started | — | — | |
| 12 | B Contracts and schema | `b/12-securities-repositories` | not started | — | — | |
| 13 | B Contracts and schema | `b/13-processing-repositories` | not started | — | — | |
| 14 | C Admission and job engine | `c/14-submission-event` | not started | — | — | |
| 15 | C Admission and job engine | `c/15-admit-submission` | not started | — | — | |
| 16 | C Admission and job engine | `c/16-submission-endpoint` | not started | — | — | |
| 17 | C Admission and job engine | `c/17-outbox-dispatcher` | not started | — | — | |
| 18 | C Admission and job engine | `c/18-queue-handler` | not started | — | — | |
| 19 | C Admission and job engine | `c/19-retry-policy` | not started | — | — | |
| 20 | D Source reading | `d/20-manifest` | not started | — | — | |
| 21 | D Source reading | `d/21-source-reader` | not started | — | — | |
| 22 | D Source reading | `d/22-filenames` | not started | — | — | |
| 23 | E CSV pipeline | `e/23-csv-structure` | not started | — | — | |
| 24 | E CSV pipeline | `e/24-csv-row-validation` | not started | — | — | |
| 25 | E CSV pipeline | `e/25-duplicate-resolution` | not started | — | — | |
| 26 | E CSV pipeline | `e/26-canonical-storage` | not started | — | — | |
| 27 | E CSV pipeline | `e/27-summary-mapper` | not started | — | — | |
| 28 | E CSV pipeline | `e/28-publish-summaries` | not started | — | — | |
| 29 | E CSV pipeline | `e/29-new-security-event` | not started | — | — | |
| 30 | F JSON pipeline | `f/30-json-reader` | not started | — | — | |
| 31 | F JSON pipeline | `f/31-json-scalars` | not started | — | — | |
| 32 | F JSON pipeline | `f/32-json-collections` | not started | — | — | |
| 33 | F JSON pipeline | `f/33-security-scalar-mapping` | not started | — | — | |
| 34 | F JSON pipeline | `f/34-collection-mapping` | not started | — | — | |
| 35 | F JSON pipeline | `f/35-publish-security-details` | not started | — | — | |
| 36 | G Read APIs | `g/36-job-status` | not started | — | — | |
| 37 | G Read APIs | `g/37-job-errors` | not started | — | — | |
| 38 | G Read APIs | `g/38-security-view` | not started | — | — | |
| 39 | G Read APIs | `g/39-market-summaries` | not started | — | — | |
| 40 | H Operations | `h/40-retention` | not started | — | — | |
| 41 | H Operations | `h/41-sweeper` | not started | — | — | |
| 42 | H Operations | `h/42-observability` | not started | — | — | |
| 43 | I Infrastructure and release | `i/43-lambda-package` | not started | — | — | |
| 44 | I Infrastructure and release | `i/44-network` | not started | — | — | |
| 45 | I Infrastructure and release | `i/45-persistent` | not started | — | — | |
| 46 | I Infrastructure and release | `i/46-application` | not started | — | — | |
| 47 | I Infrastructure and release | `i/47-deploy-workflows` | not started | — | — | |
| 48 | I Infrastructure and release | `i/48-smoke` | not started | — | — | |

## Deployment

Nothing is deployed. No AWS resources have been created by this repository.

## Test-case coverage

Implemented so far (stack A): U-ARCH-01, U-ARCH-02, U-ARCH-03, U-VAL-01, U-VAL-02, and the serialisation part of I-READ-08. Each PR row lists the cases it added.

## Reviews

| Stack | Reviewer | Heads reviewed | Outcome |
|---|---|---|---|
| A | Reviewer agent, 2026-10-02 | 24b8bbe, 79b05c9, f075dbe, 5f72378, 4702b07 | 2 blocking and 9 required findings, all fixed on the layer they belong to; suggestions adopted except request-ID correlation (W-5) and pinning the runner image (C-5, partly). The fixed heads have not been re-reviewed yet |
