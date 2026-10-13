# 0006 — A public metrics endpoint for security data completeness

Date: 2026-10-13
Status: accepted

## Context

The owner wanted business metrics showing how complete the securities data is: how many ISINs the system knows, and how many are missing details, cash flows, current ratings, a coupon rate, or a collateral status. The owner defined each metric on 2026-10-13.

## Decision

- `GET /v1/metrics` returns six current counts in one JSON object. It is public like the other read endpoints, served by the read function under its read-only database role, and it shows counts only, never ISINs.
- Every count includes ISIN-only securities, and every count is a gauge: its value now, with no history and no `asOf`.

| Field | Counts ISINs where |
|---|---|
| `securitiesCount` | the ISIN is in `securities_data.securities`, whatever else is stored |
| `securitiesCountWithoutDetails` | all 12 detail fields are null (a coupon rate from either source is a detail), and the security has no cash flow, listing, rating, or collateral asset; daily market summaries are not details |
| `securitiesCountWithoutCashFlows` | the security has no cash flow |
| `securitiesCountWithoutCurrentRatings` | the security has no rating with `source_category = 'CURRENT'`; earlier ratings do not count |
| `securitiesCountWithoutCouponRate` | `coupon_rate_value` is null, whatever the source |
| `securitiesCountWithoutCollateralStatus` | `collateral_status` is null |

- One SQL statement computes all six, so they describe the same moment. It scans `securities` once and checks each collection through an existing index that starts with `isin`, so no new index is needed.

## Consequences

- LLD section 20 lists the endpoint, and new section 20.5 defines the metrics.
- The read OpenAPI YAML and JSON gain `/v1/metrics` and the `Metrics` schema. The API Gateway gains the route `GET /v1/metrics`, making 6 read routes and 5 documentation routes.
- Tests: U-REV-10, I-READ-11; `ReadContractIT` checks the response against the OpenAPI schema, and `FunctionRolesIT` runs the query as the reader role.
