# Decision records

Decisions up to 2026-10-02 are recorded in the LLD itself. A design decision made after that date gets a short record here and an update to the LLD in the same PR.

File name: `NNNN-short-title.md`, numbered from `0001`. Each number is used once, and every record is listed below; `make check-docs` fails otherwise.

## Records

- [0001 — No NAT gateway, fetch Lambdas outside the VPC, and gated releases](0001-no-nat-and-gated-releases.md)
- [0002 — Daily outbox sweeper and weekly retention](0002-daily-outbox-sweeper-weekly-retention.md)
- [0003 — An ISIN list replaces the per-trade-date summary list](0003-isin-list-replaces-trade-date-summaries.md)
- [0004 — The bhavcopy coupon rate comes first, NSDL's is the backup](0004-bhavcopy-coupon-rate-first.md)
- [0005 — Request a security's details again: missing coupon rate or stale details](0005-request-details-again-without-coupon.md)
- [0006 — A public metrics endpoint for security data completeness](0006-security-metrics-api.md)
- [0007 — Read NSDL's interest cash-flow list too](0007-interest-cash-flow-schedule.md)
- [0008 — Request details again while any counted detail is missing; no monthly refresh](0008-rerequest-any-missing-detail.md)
- [0009 — An interest cash flow's amount comes from amountRedeem](0009-interest-cash-flow-amount.md)
- [0010 — Store each security's total issue size and seniority in repayment](0010-issue-size-and-seniority.md)

## Template

```markdown
# NNNN — Title

Date:
Status: accepted | superseded by NNNN

## Context
What prompted the decision.

## Decision
What was decided, stated so it can be implemented.

## Consequences
What changes in the LLD, contracts, OpenAPI files, tests, or operations.
```
