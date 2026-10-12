# 0004 — The bhavcopy coupon rate comes first, NSDL's is the backup

Date: 2026-10-12
Status: accepted

## Context

The coupon rate came only from NSDL's `couponRate`. Some NSDL values are text such as `8.1% (cat I & II and 0.2% additn int for cat III)` or `RESET RATE (REFER REMARKS)`, so on 2026-10-11 nine securities had no coupon rate and their jobs ended `COMPLETED_WITH_ERRORS` with `INVALID_DECIMAL`. The BSE debt bhavcopy has a `COUP0N (%)` column (spelled with a zero) with plain numbers. In the 2026-10-01 file it had 379 decimals, 2 zeros, and 64 blanks, and no text. For 303 of the 305 securities that had both rates, the two sources agreed. The owner decided on 2026-10-12 to take the rate from the bhavcopy and use NSDL only as a backup.

## Decision

- **The bhavcopy is the primary source.** The BSE source contract `bse-debt-bhavcopy-csv-v2` reads `COUP0N (%)` as `coupon_rate`, of type `percent`. `bse-debt-bhavcopy-mapping-v2` maps it to the row's security through a new `securityFields` section: `coupon_rate: couponRate`.
- **Zero and blank mean no update.** `zeroToNull` is a new normalizer: `0`, `0.00`, and similar values have no value.
- **An invalid bhavcopy rate drops only the rate.** `onInvalid: ignoreValue` is a new CSV field option. With it, an invalid value makes the field `IGNORED` instead of failing the row. The row's summary is still published, the error is stored as a validation issue with no rejected record and the action taken, and the job ends `COMPLETED_WITH_ERRORS`. A field with this option cannot be required and cannot be a row-rule operand.
- **Among bhavcopies, the latest trade date wins.** Each security stores the coupon rate's source (`BSE_BHAVCOPY` or `NSDL`) and, for a bhavcopy, its trade date.
  - A bhavcopy for an older trade date never replaces a newer one's rate.
  - A different rate from the same or a later trade date replaces the stored rate, its source, and the security's `updated_at`.
  - The same rate from a later trade date only moves the stored trade date forward. That is not a change to the security.
- **NSDL is the backup.** An NSDL rate is stored only while no bhavcopy has supplied one. A rate stored before this release, or without a source, counts as NSDL's.
- **JSON percents are read up to the first `%`.** `keepBeforeFirstPercent` is a new normalizer that replaces `stripTrailingPercent` in the fixed JSON percent rule. `8.1% (cat I …)` is 8.1. Text without a number before its first `%` is still `INVALID_DECIMAL`. This applies to every JSON percent, asset coverage included.
- **Disagreements are logged, not stored.** A rate that differs from the other source's stored rate is logged as a warning, `Coupon rates differ`, with the job, ISIN, and both values. As with every warning (LLD section 16), it is not an error and does not change the job's status.

## Consequences

- LLD sections 2.2, 5.2, 13.4, 15.3, 17.5, and 22.1 are updated. The BSE contracts move to v2; the NSDL contracts are unchanged.
- Migration V7 adds `coupon_rate_source` and `coupon_rate_trade_date` to `securities_data.securities` and marks every stored rate as `NSDL`. It is additive: no constraint ties the new columns to the rate, because the previous release keeps writing rates without a source while the migration runs.
- A job admitted before the release is pinned to the v1 BSE contracts, so the new release fails its attempts and the job ends `FAILED`. Deploy when no BSE job is queued, or rerun such a job from the fetch service.
- Existing securities get a bhavcopy rate from the next bhavcopy processed after the release. To fill them sooner, rerun recent bhavcopies with `force`.
- Tests: U-NUM-06, U-NUM-07, U-JSON-14, U-CSV-13, U-CON-05, U-MAP-05, U-COUPON-01 to U-COUPON-06, I-COUPON-01, I-COUPON-02, I-CSV-09.
