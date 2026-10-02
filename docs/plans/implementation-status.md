# Implementation status

Factual record of progress against the [implementation plan](data-processing-service-implementation-plan.md). Update it in every PR.

Rules: a PR link is added only when the PR exists. `State` is one of `not started`, `in progress`, `in review`, `merged`. `Verified checks` lists what actually ran and passed on the merged head. Planned or mocked work is never marked deployed or live-tested.

Last updated: 2026-10-02. No application code exists yet.

| PR | Stack | Branch | State | PR link | Verified checks | Notes |
|---|---|---|---|---|---|---|
| 00 | — | `docs/implementation-plan` | in review | — | YAML contracts parse; OpenAPI YAML and JSON equivalent; relative links resolve | Documentation baseline and NSDL fixtures |
| 01 | A Foundation | `a/01-build-skeleton` | not started | — | — | |
| 02 | A Foundation | `a/02-quality-gates` | not started | — | — | |
| 03 | A Foundation | `a/03-ci-workflow` | not started | — | — | |
| 04 | A Foundation | `a/04-value-types` | not started | — | — | |
| 05 | A Foundation | `a/05-web-baseline` | not started | — | — | |
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

No catalogue test cases are implemented yet. When a PR merges, list its test-case IDs in its `Notes` cell.
