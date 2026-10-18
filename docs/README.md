# Documentation map

| Document | Role | Authority |
|---|---|---|
| [specs/structured-file-processing-lld.md](specs/structured-file-processing-lld.md) | Low-level design | Authoritative for behaviour. Its sections agree; a contradiction between them is a defect to fix |
| [../contracts/](../contracts) | Four YAML processing contracts | Authoritative for field paths, rules, and mappings; override the YAML snippets in LLD section 6 |
| [specs/data-processing-service-openapi.yaml](specs/data-processing-service-openapi.yaml) (and `.json`) | Submission API, shared with data-fetch-service | Authoritative for `POST /v1/event-ingestions` |
| [specs/data-processing-service-read-openapi.yaml](specs/data-processing-service-read-openapi.yaml) (and `.json`) | Public read API | Authoritative for the five `GET` endpoints |
| [specs/structured-file-processing-spec.md](specs/structured-file-processing-spec.md) | Original functional specification | Historical; the LLD supersedes it where they differ |
| [plans/completed/data-processing-service-implementation-plan.md](plans/completed/data-processing-service-implementation-plan.md) | Original build plan: 48 PRs in nine stacks, all merged | Historical |
| [plans/active/](plans/active/README.md) | Plans for changes that span several PRs | Authoritative for the order and scope of that change while it is in progress |
| [plans/tech-debt.md](plans/tech-debt.md) | Known gaps no current PR fixes | Factual record |
| [plans/implementation-context-spec.md](plans/implementation-context-spec.md) | Toolchain pins and deployment inputs | Authoritative for versions and pending inputs |
| [plans/implementation-status.md](plans/implementation-status.md) | What is actually built and verified | Factual record |
| [engineering/context-management.md](engineering/context-management.md) | When agents compact or clear their context, and what to keep | Authoritative for agent sessions |
| [engineering/java-standards.md](engineering/java-standards.md) | Code structure, naming, readability | Authoritative for code style |
| [engineering/testing-strategy.md](engineering/testing-strategy.md) | Test sizes and the required test-case catalogue | Authoritative for what must be tested |
| [engineering/pr-review.md](engineering/pr-review.md) | Review sequence and checklist | Authoritative for review |
| [decisions/](decisions/README.md) | Decision records made after 2026-10-02 | Each record also updates the LLD |

External references, owned by [data-fetch-service](https://github.com/maverickuser/data-fetch-service): its LLD (`docs/specs/data-puller-service-lld.md`) defines the manifest it produces and the event it consumes.

The YAML and JSON forms of each OpenAPI document must stay equivalent; `make check-contracts` verifies this.
