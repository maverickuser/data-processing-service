# 0009 — An interest cash flow's amount comes from amountRedeem

Date: 2026-10-14
Status: accepted

## Context

Decision 0007 read NSDL's `interestCashFlowSchedule` with the same six fields as `cashFlowSchedule`, so its amount came from `amountPayable`. NSDL leaves `amountPayable` null in that list and gives the interest paid in `amountRedeem`. In the 952 coupon files fetched by 2026-10-14, all 285 interest entries (111 securities) had a numeric `amountRedeem` and a null `amountPayable`, and no `cashFlowSchedule` entry had an `amountRedeem`. Every stored interest cash flow therefore had no amount. On 2026-10-14 the owner asked for `amountRedeem` to be read for interest entries.

## Decision

- `nsdl-security-json-v3` reads the `interest_cash_flows` field `amount_payable` from `amountRedeem`. All other paths are unchanged, and the scheduled list still reads `amountPayable`.
- `nsdl-security-mapping-v3` only follows the new source contract; the mapping is unchanged.
- Migration V10 is a one-off repair. It deletes the stored interest cash flows that have no amount, because the amount is part of a cash flow's identity and the corrected entries would otherwise be stored beside them. Collections stay append-only for every other write.

## Consequences

- The NSDL contracts move to v3. A JSON job admitted before the release is pinned to v2 and fails after the release, so deploy when no NSDL job is queued, or rerun such a job from the fetch service.
- After the release, the NSDL requests of the 111 affected securities are rerun with `force`, which stores their interest entries again with amounts. An NSDL job that runs between the migration and the alias promotion still uses v2 and may store an interest entry without an amount again; that ISIN is rerun too.
- An interest entry that matches a scheduled payment on every field, amount included, is now stored once (decision 0007).
- LLD sections 13.2 and 22.1 and the read OpenAPI's `CashFlow.sourceCategory` description are updated.
- Tests: U-JSON-16, I-JSON-10.
