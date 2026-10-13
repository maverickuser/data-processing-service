# 0008 — Request details again while any counted detail is missing; no monthly refresh

Date: 2026-10-14
Status: accepted (supersedes the monthly refresh of 0005)

## Context

Decision 0005 requested a traded security's details again only while its coupon rate was unknown, and also refreshed every security whose details and last request were 30 days old. The metrics of decision 0006 showed securities with a coupon but no cash flows, current ratings, or collateral status, which that rule never asked for again. On 2026-10-14 the owner asked for any missing detail to trigger a retry, and for the monthly refresh to be removed for now and revisited later.

## Decision

- An existing traded security is requested again when, after the bhavcopy's coupon rates are stored, it lacks any of the four details `GET /v1/metrics` counts:
  - a coupon rate, from either source;
  - cash flows;
  - current ratings;
  - a collateral status.
- The request limits are unchanged: the first request plus two retries, 3 in all, at least 1 day apart. A security created by the bhavcopy is always requested.
- The monthly refresh is removed. Nothing requests a security whose four details are present.
- The publication reads the three new presence flags in the same statement that locks the traded securities, in ISIN order.

## Consequences

- LLD sections 14.1 and 14.4 are updated. Decision 0005's refresh no longer applies.
- More securities qualify than under the coupon-only rule. Each is still requested at most 3 times in all, so a security whose NSDL data genuinely has no cash flows or ratings stops after its retries.
- No migration.
- Tests: U-DETAILS-01 and U-DETAILS-04 are redefined; I-DETAILS-02 is redefined.
