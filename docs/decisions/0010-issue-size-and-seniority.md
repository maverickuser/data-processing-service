# 0010 — Store each security's total issue size and seniority in repayment

Date: 2026-10-15
Status: accepted

## Context

NSDL's instrument details (`/bdsinfo/instruments`) give `instrumentsVo.instruments.totalIssueSize` and `instrumentsVo.instruments.seniorityRepayment`, and the service read neither. For `INE831R08076` they are `"200"` and `"Subordinate Tier 1"`. The issue size is in crore rupees: the same file's `furtherIssueVo` gives the issue as 2,000,000,000 rupees. On 2026-10-15 the owner asked for both to be stored on each security, and for every existing security to be backfilled once the change is released.

## Decision

- `nsdl-security-json-v4` adds two scalars:
  - `total_issue_size` from `$.instrumentsVo.instruments.totalIssueSize`, a non-negative decimal;
  - `seniority_repayment` from `$.instrumentsVo.instruments.seniorityRepayment`, text.
- `nsdl-security-mapping-v4` maps them to the security fields `totalIssueSize` and `seniorityRepayment`.
- Both follow the rules of every other NSDL scalar: no-value inputs never erase a stored value, and a changed value updates the security's `updated_at`.
- The read API's `Security` gains `totalIssueSize` (a number, in crore rupees as NSDL gives it) and `seniorityRepayment`.
- Both count as detail fields for `securitiesCountWithoutDetails` (decision 0006), which now checks 14 fields.

## Consequences

- Migration V11 adds `total_issue_size NUMERIC CHECK (>= 0)` and `seniority_repayment TEXT` to `securities_data.securities`. It is additive.
- The NSDL contracts move to v4. A JSON job admitted before the release is pinned to v3 and fails after the release, so deploy when no NSDL job is queued.
- Existing securities are backfilled by force-rerunning each one's latest NSDL fetch run after the release.
- LLD sections 13.2, 15.3, 17.5, and 22.1 and the read OpenAPI are updated.
- Tests: U-JSON-17, I-JSON-11, I-READ-12.
