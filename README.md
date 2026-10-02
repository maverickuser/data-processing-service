# data-processing-service

Validates and stores bond data fetched by [data-fetch-service](https://github.com/maverickuser/data-fetch-service): BSE debt bhavcopy CSV becomes daily market summaries, and NSDL per-ISIN JSON becomes security details, cash flows, listings, ratings, and collateral assets. Java and Spring Boot on AWS (Lambda, API Gateway, RDS PostgreSQL, SQS, S3).

Status: design complete, implementation not started. See [implementation status](docs/plans/implementation-status.md).

- Contributors and coding agents start with [AGENTS.md](AGENTS.md).
- Design: [low-level design](docs/specs/structured-file-processing-lld.md).
- Plan: [implementation plan](docs/plans/data-processing-service-implementation-plan.md).
- All documents: [documentation map](docs/README.md).
