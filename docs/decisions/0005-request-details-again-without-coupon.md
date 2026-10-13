# 0005 — Request a security's details again while its coupon rate is unknown

Date: 2026-10-13
Status: accepted

## Context

A bhavcopy requested a security's details only once, when it created the security (LLD section 14.4). If that fetch failed, came back empty, or gave no usable coupon rate, the security stayed without one, and later trades never asked again. On 2026-10-13 the owner asked for a security without a coupon rate to be requested again, at most three times.

## Decision

- When a bhavcopy is published, a security is requested in either of two cases:
  - this bhavcopy created it, which is the first request as before;
  - it already existed, and all of the following hold after this bhavcopy's coupon rates are stored:
    - it has no coupon rate,
    - fewer than 3 requests were made for it,
    - the latest request was at least 1 day ago.
- A security already has a coupon rate if one is stored or if this bhavcopy reports one.
- At most 3 requests are made per security, the first one included.
- Only accepted bhavcopy rows trigger a request. No sweep looks for securities that do not trade.
- Each security stores `details_request_count` and `details_requested_at`. Every request sent increments the count and sets the time, in the same transaction as its outbox event. A request is not a change to the security, so `updated_at` is unchanged.
- The publication locks every traded security once, in ISIN order, before storing coupon rates and deciding requests. Two bhavcopies publishing at once therefore cannot deadlock.

## Consequences

- LLD sections 14.1, 14.3, 14.4, and 22.1 are updated. The rule "later trades never initiate a fresh fetch" no longer holds for a security without a coupon rate.
- Migration V8 adds both columns. It sets every existing security to count 1, with its creation time as the request time. A security created by the previous release while the migration runs gets count 0.
- A security that never gets a coupon rate, such as an index-linked bond, is requested 3 times in all and then never again.
- Tests: U-DETAILS-01 to U-DETAILS-03, I-DETAILS-01.
