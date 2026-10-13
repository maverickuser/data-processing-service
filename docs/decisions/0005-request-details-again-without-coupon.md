# 0005 — Request a security's details again: missing coupon rate or stale details

Date: 2026-10-13
Status: accepted; its monthly refresh superseded by 0008

## Context

A bhavcopy requested a security's details only once, when it created the security (LLD section 14.4). If that fetch failed, came back empty, or gave no usable coupon rate, the security stayed without one, and later trades never asked again. On 2026-10-13 the owner asked for a security without a coupon rate to be requested again, at most three times. The owner also asked for a security whose details are a month old to be requested again, because new cash flows may have been published.

## Decision

- When a bhavcopy is published, a security is requested in either of two cases:
  - this bhavcopy created it, which is the first request as before;
  - it already existed, and all of the following hold after this bhavcopy's coupon rates are stored:
    - it has no coupon rate,
    - fewer than 3 requests were made for it,
    - the latest request was at least 1 day ago.
- A security already has a coupon rate if one is stored or if this bhavcopy reports one.
- **Monthly refresh.** An existing traded security is also requested when its `updated_at` and its latest request are both at least 30 days old, so that new cash flows reach it. Both dates are checked because an identical NSDL reply leaves `updated_at` unchanged, and checking it alone would request on every bhavcopy. The refresh has no limit on count; each one still increments the counter.
- A refresh appends: collections stay append-only (LLD section 13.6), so only new cash flows, listings, ratings, and assets are added. Scalar fields take the newer valid values as before (section 13.5).
- At most 3 requests are made per security, the first one included.
- Only accepted bhavcopy rows trigger a request. No sweep looks for securities that do not trade.
- Each security stores `details_request_count` and `details_requested_at`. Every request sent increments the count and sets the time, in the same transaction as its outbox event. A request is not a change to the security, so `updated_at` is unchanged.
- The publication locks every traded security once, in ISIN order, before storing coupon rates and deciding requests. Two bhavcopies publishing at once therefore cannot deadlock.

## Consequences

- LLD sections 14.1, 14.3, 14.4, and 22.1 are updated. The rule "later trades never initiate a fresh fetch" no longer holds for a security without a coupon rate.
- Migration V8 adds both columns. It sets every existing security to count 1, with its creation time as the request time. A security created by the previous release while the migration runs gets count 0.
- A security that never gets a coupon rate, such as an index-linked bond, is requested 3 times in all and then never again.
- Every traded security is requested at least once every 30 days or so, which adds one fetch per traded security per month.
- Tests: U-DETAILS-01 to U-DETAILS-04, I-DETAILS-01, I-DETAILS-02.
