# 0007 — Read NSDL's interest cash-flow list too

Date: 2026-10-14
Status: accepted

## Context

NSDL's coupon details carry two cash-flow lists. The contract read only `cashFlowSchedule[*]`. For some securities, such as `INE031A07840` and `INE219X07264`, NSDL's `cashFlowSchedule` holds only a placeholder entry, and their payments appear only in `interestCashFlowSchedule[*]`. Those securities therefore had no cash flows stored. Of 150 sampled coupon files, 127 had only the scheduled list and 21 had both. On 2026-10-14 the owner asked for the interest list to be read as a separate list with the same fields as the scheduled one.

## Decision

- The NSDL source contract `nsdl-security-json-v2` adds the collection `interest_cash_flows`, read from `$.coupensVo.cashFlowScheduleDetails.interestCashFlowSchedule[*]`. It has the same six fields and paths as `cash_flows`: `cashFlowsEvent`, `recordDate`, `dueDate`, `amountPayable`, `paymentDate`, `newFaceVal`.
- `nsdl-security-mapping-v2` maps both collections to `securities_data.security_cash_flows`, with the constant `sourceCategory` set to `SCHEDULE` for the scheduled list and `INTEREST` for the interest list.
- A cash flow's source category is stored and shown in the read API as `sourceCategory`, but it is not part of the cash flow's identity. The unique index over the business columns is unchanged, so an entry identical in both lists is stored once, with the category of the list read first.
- NSDL's interest entries give their amount in `amountRedeem` and leave `amountPayable` empty. As agreed, only the same fields are read, so an interest entry's `amountPayable` is usually null. Where it matches a scheduled payment on every other field it is still a separate entry, because the scheduled one has an amount.

## Consequences

- Migration V9 adds `source_category` (`SCHEDULE` or `INTEREST`, default `SCHEDULE`) to `security_cash_flows`. Cash-flow inserts use `ON CONFLICT DO NOTHING` without a target. The change is additive: the previous release keeps its conflict target, which is the unchanged unique index.
- The NSDL contracts move to v2. A JSON job admitted before the release is pinned to v1, so its attempts fail after the release. Rerun such a job from the fetch service.
- Securities fetched before the release get interest entries the next time their details are requested or refreshed (decision 0005).
- The read OpenAPI's `CashFlow` gains `sourceCategory`. LLD sections 13.2, 13.6, 17.1, and 22.1 are updated.
- Tests: U-JSON-15, I-JSON-09.
