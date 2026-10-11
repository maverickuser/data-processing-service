# 0001 — No NAT gateway, fetch Lambdas outside the VPC, and gated releases

Date: 2026-10-10 (network decisions taken by the owner on 2026-10-07; confirmed for PR 47 on 2026-10-10)
Status: accepted

## Context

PR 47 adds the first real deployment. The shared network in `cloud-platform-network` was planned with one NAT gateway, used by this service only so the migration function could read the RDS master secret, and by the fetch service's Lambdas to reach public sources. A release also has to change the schema while functions are serving.

## Decision

- The shared network has no NAT gateway by default. The processing functions reach only the VPC, S3 through the gateway endpoint, and SQS and Secrets Manager through interface endpoints. The migration function reads the RDS master secret through the Secrets Manager endpoint. The deploy workflow refuses to apply when that endpoint is missing, not available, or lacks private DNS.
- The data-fetch-service Lambdas run outside the shared VPC, so they need neither NAT nor endpoints there.
- On the first release, API routes, queue consumption, and schedule targets stay absent until the migration succeeds. On later releases the old `live` aliases keep serving while the new migration runs; the aliases move only after it succeeds.
- Because old code runs against the new schema during every later release, migrations are additive: they add tables, columns, indexes, or grants, and never drop, rename, or narrow anything the previous release reads or writes. A removal takes two releases: stop using it, then drop it.

## Consequences

- LLD sections 1, 4, 23.5, 23.6, and 23.7, the implementation plan, the context spec, and `infra/README.md` describe the endpoint-only network and the release order.
- `scripts/check_network_endpoint.sh` and `scripts/migrate_and_promote.sh` implement the gate and the order; `scripts/test_release_scripts.py` covers their refusals.
- Reviewers block a migration that drops, renames, or narrows a column or table still used by the release before it.
