# 0003 — An ISIN list replaces the per-trade-date summary list

Date: 2026-10-11
Status: accepted

## Context

The read API had two daily-market-summary endpoints: one per ISIN, and `GET /v1/daily-market-summaries?tradeDate=` for every summary of one trade date. The owner asked for the per-date list to be removed and replaced by a list of ISINs, so a caller first finds the securities and then reads each one's summaries.

## Decision

- `GET /v1/daily-market-summaries` is removed from the service, the read OpenAPI document, and the API Gateway routes. A request for it now gets the router's `404`.
- `GET /v1/securities` returns every ISIN in `securities_data.securities`, ISIN-only securities included, as plain strings in ascending order, 50 per page, with the same opaque `pageToken`/`nextToken` cursor as the other lists. It takes no filters. A changed token, or one from another list, is `400`.
- `GET /v1/securities/{isin}/daily-market-summaries` and `GET /v1/securities/{isin}` are unchanged.

## Consequences

- LLD section 20 lists the new endpoint; section 20.2 describes only the per-ISIN list; new section 20.4 describes the ISIN list.
- The read OpenAPI YAML and JSON replace the operation and add the `IsinPage` schema. The read API stays at version `1.0.0`: nothing outside this platform consumes it yet.
- Test case I-READ-05 is retired; U-REV-09 and I-READ-10 cover the ISIN list.
- No migration: the list reads the `securities` primary key, which is already indexed.
