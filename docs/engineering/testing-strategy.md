# Testing strategy and required test cases

Date: 2026-10-02. This is the catalogue of test cases the implementation must contain. Each case has a stable ID. A PR lists the IDs it adds; `docs/plans/implementation-status.md` records which are present and passing. Adding behaviour without adding or extending a case here is a review finding.

LLD references are to `docs/specs/structured-file-processing-lld.md`.

## Test sizes

Following Google's small/medium/large split:

| Size | Suffix | Runs with | May use | Coverage gate |
|---|---|---|---|---|
| Unit (small) | `*Test` | `make test-unit` | Plain Java, in-memory fakes of ports. No Spring context, no Docker, no network, no clock, no sleeping | Line coverage strictly greater than 95% of authored production code |
| Integration (medium) | `*IT` | `make test-integration` | Spring Boot context, Testcontainers PostgreSQL 16, LocalStack S3 and SQS, Lambda handlers invoked directly with API Gateway, SQS, and schedule event fixtures | Not counted towards the unit gate |
| Smoke (large) | workflow only | Final release workflow | Deployed service, real AWS | Runs after deployment; never on a pull request |

## Rules

- Test behaviour through the public surface of a class or use case. Do not assert on private state or call order unless the order is the behaviour (for example transaction boundaries).
- Prefer hand-written in-memory fakes of ports over mocks. Use Mockito only to inject a failure that a fake cannot express simply. The one standing exception is JDBC repositories: their unit tests mock Spring's JDBC operations interface and assert the SQL and bound parameters, and every repository method also has a database integration test.
- Time and IDs are injected. Tests use a fixed `Clock` and a deterministic ID supplier.
- One behaviour per test. Method names are sentences: `keepsLastValidRowWhenIsinRepeats`.
- Table-driven cases use JUnit 5 `@ParameterizedTest`. Each row is a named case.
- Fixtures live in `src/test/resources/fixtures/`. They are sanitised, small, and committed. The five NSDL sample payloads and one bhavcopy extract are golden inputs with golden canonical and mapped outputs.
- Assertions use AssertJ. Compare `BigDecimal` with `isEqualByComparingTo`.
- An integration test owns its data: it creates what it needs and does not depend on test order.
- No test may be disabled without a linked issue and a recorded reason.

## Unit test cases

### Value types and normalization (`shared`, `canonical`)

| ID | Case | LLD |
|---|---|---|
| U-VAL-01 | `Isin` trims and uppercases; blank and whitespace-only are rejected; no length or check-digit validation is applied | 5.2 |
| U-VAL-02 | `Percent` parses `8.94`, `"8.94"`, `"8.94%"`, `" 100 % "` to the same value with unit `PERCENT`; negative is rejected; zero and values above 100 are accepted | 13.4, 15.2 |
| U-VAL-03 | `TradeDate` filename parsing: `BSE_fgroup01012026.csv` gives exchange `BSE` and `2026-01-01`; missing prefix, missing digits, and `32132026` are rejected | 2.1 |
| U-NUM-01 | Grouped numbers accepted: `1234.56`, `1,234.56`, `1,23,456.78`, `0`, `0.00`, leading and trailing spaces | 5.2 |
| U-NUM-02 | Grouped numbers rejected: `1,2,34`, `12,34.5,6`, `1,,234`, `,123`, `1.234,56`, `1e5`, `₹100`, `(100)`, `abc` | 5.2 |
| U-NUM-03 | High-precision decimals keep every digit; no rounding; scale is preserved in the canonical string | 5.2 |
| U-NUM-04 | Whole-number fields: `14.00` becomes `14`; `14.50` fails with `NOT_WHOLE_NUMBER` | 5.2 |
| U-NUM-05 | Negative price, volume, trade count, turnover, and face value fail with `NEGATIVE_VALUE` and keep the parsed number; zero passes | 4.2, 5.2 |
| U-DATE-01 | Dates accept `DD-MM-YYYY` and `YYYY-MM-DD`, normalise to ISO; `32-13-2026`, `2026-02-30`, `10/06/2019` are rejected | 13.4 |
| U-PRES-01 | Missing, JSON `null`, `""`, `"  "`, `"-"`, `"N.A."` are all classified as no-value and are not errors | 13.4 |
| U-TEXT-01 | JSON text fields accept strings only; number, boolean, object, and array are field errors; case is preserved | 13.4 |

### Contracts (`contract`)

| ID | Case | LLD |
|---|---|---|
| U-CON-01 | All four committed contracts load and validate | 13.1 |
| U-CON-02 | Unknown rule name, unknown type, duplicate field, mapping of an undeclared canonical field, and a mapping contract naming a missing source contract each fail startup with a clear message | 6 |
| U-CON-03 | Dataset URN resolves to its source and mapping contracts; unknown URN is rejected | 2.1 |
| U-CON-04 | Contract content hash is stable for identical content and changes when content changes | 6.2 |

### CSV stage 1 (`canonical.csv`)

| ID | Case | LLD |
|---|---|---|
| U-CSV-01 | Headers match after trimming and case-folding (`Number of Trades ` with trailing space); columns may be reordered; extra columns are ignored | 5.1 |
| U-CSV-02 | A missing selected header (including `FACE VALUE`) rejects the file with `REQUIRED_HEADER_MISSING` | 5.1 |
| U-CSV-03 | Duplicate headers after normalization, including unselected ones, reject the file | 5.1 |
| U-CSV-04 | Empty file and header-only file are rejected with `EMPTY_FILE` | 5.1 |
| U-CSV-05 | Broken quoting, or a record with the wrong cell count late in the file, rejects the whole file with `MALFORMED_CSV` and produces no accepted rows | 5.1 |
| U-CSV-06 | UTF-8 byte-order mark is ignored; invalid UTF-8 is `MALFORMED_CSV`; blank lines are skipped and not counted | 5.1 |
| U-CSV-07 | Quoted grouped number `"1,14,200.00"` is read as one cell and parsed | 5.1 |
| U-CSV-08 | Blank optional fields pass as null; blank ISIN fails with `REQUIRED_VALUE_MISSING`; leading zeros in security code survive | 5.2 |
| U-CSV-09 | Every applicable error on a row is collected, not only the first | 5.3 |
| U-CSV-10 | Price comparisons run only when both operands are present and valid; each of the five rules has a passing and failing case | 5.3 |
| U-CSV-11 | Record numbers are one-based with the header as record 1; a record spanning two physical lines has one record number | 4.2 |
| U-DUP-01 | Valid rows 2 and 5 and invalid row 9 for one ISIN: row 5 accepted, row 2 superseded with a reference to 5, row 9 invalid | 5.4 |
| U-DUP-02 | Only invalid rows for an ISIN produce no accepted row | 5.4 |
| U-DUP-03 | ISINs differing only by case or surrounding spaces are the same ISIN | 5.4 |
| U-CSV-12 | Counts: accepted + invalid + superseded equals source records | 20.3 |

### JSON stage 1 (`canonical.json`)

| ID | Case | LLD |
|---|---|---|
| U-JSON-01 | Each of the five sample payloads yields the expected canonical scalars and collection entries (golden files) | 13.2 |
| U-JSON-02 | All paths are applied to every file regardless of filename suffix; a file with no selected paths (redemptions) yields nothing and no error | 13.1, 19 |
| U-JSON-03 | Property names match case-sensitively; `IssuerName` does not satisfy `issuerName` | 13.4 |
| U-JSON-04 | Filename ISIN: `INE831R08076_ratings.json` and `INE831R08076.json` both give the ISIN; `_ratings.json` is `INVALID_SOURCE_FILENAME` | 13.1 |
| U-JSON-05 | Mixed filename ISINs fail the whole request | 13.1 |
| U-JSON-06 | Payload ISIN differing from filename ISIN is a warning only and produces no validation issue | 13.1, 16 |
| U-JSON-07 | Malformed JSON, and duplicate property names in one object, skip that file with an error; other files continue | 13.7 |
| U-JSON-08 | Object where an array is expected (`currentRatings`) rejects that collection with path and expected/actual types; other sections continue | 13.7 |
| U-JSON-09 | An invalid field inside a collection entry is rejected alone; the entry keeps its other valid values | 13.4 |
| U-JSON-10 | An entry with no valid non-blank value is skipped | 13.4 |
| U-JSON-11 | `issuePrice` is canonicalised as `original_face_value` from a number and from a grouped string | 13.2 |
| U-JSON-13 | Cash flows from the `INE0O7U07046` sample: `Partial Redemption` entries carry `new_face_value` (8750, 7500, …); `Interest` and `Full Redemption` entries have none | 15.4 |
| U-JSON-12 | Numbers are read as `BigDecimal`; `89400` and `"89,400.00"` normalise to equal values | 13.6 |

### Stage 2 and publication decisions (`mapping`, `publication`)

| ID | Case | LLD |
|---|---|---|
| U-MAP-01 | CSV canonical row maps to a daily market summary with manifest `tradeDate` and `exchangeName`, including `faceValue` | 2.2 |
| U-MAP-02 | Only `ACCEPTED` rows are mapped | 6.2 |
| U-MAP-03 | JSON canonical scalars map to the security fields in LLD 15.3, including `originalFaceValue` | 15.3 |
| U-MAP-04 | Current and earlier ratings map to the same target with `sourceCategory` `CURRENT` and `EARLIER` | 15.6 |
| U-SCAL-01 | A scalar changes only when the normalised value differs; an identical value produces no update | 13.5 |
| U-SCAL-02 | No-value inputs never erase an existing value | 13.5 |
| U-SCAL-03 | Two files supply different valid values: later S3 `LastModified` wins; object key breaks ties; precedence is per field | 13.5 |
| U-COLL-01 | Entries differing only in missing versus `null` versus placeholder versus invalid for a field are the same entry | 13.4 |
| U-COLL-02 | A changed combination of values is a new entry; nothing is overwritten | 13.6 |
| U-UNSEC-01 | `Unsecured` clears coverage basis and coverage and suppresses asset appends | 15.2 |
| U-UNSEC-02 | `Unsecured` with meaningful coverage or assets records `CONFLICTING_COLLATERAL_DATA`, applies the status, and the outcome is `COMPLETED_WITH_ERRORS` | 15.2 |
| U-OUT-01 | CSV outcome: all accepted → `COMPLETED`; some rejected → `COMPLETED_WITH_ERRORS`; none accepted → `FAILED` | 9 |
| U-OUT-02 | JSON outcome: usable data and no errors → `COMPLETED`; unchanged valid data counts as usable; only placeholders → `FAILED` | 13.8 |

### Admission, jobs, outbox, review (`admission`, `job`, `outbox`, `review`)

| ID | Case | LLD |
|---|---|---|
| U-ADM-01 | A valid BSE and a valid NSDL submission event are accepted; each required attribute missing or wrong is a `400` problem | 2.1 |
| U-ADM-02 | Same key and same payload returns the original receipt; same key and different payload is a conflict | 2.1 |
| U-ADM-03 | BSE submission without `inputs.exchangeName` or `inputs.tradeDate` is rejected | 2.1 |
| U-ADM-04 | Ordering group is `trade-date:{date}` for BSE and `isin:{isin}` for NSDL | 8.2, 13.7 |
| U-JOB-01 | Retry policy: temporary failure retries after 1 minute then 5 minutes; third failure is `FAILED`; permanent failure never retries | 8.3, 21 |
| U-JOB-02 | A redelivered message for a terminal job does nothing | 8.1 |
| U-JOB-03 | Manifest and submission disagreeing on dataset, run ID, inputs, or fingerprint fails the job | 2.1 |
| U-SRC-01 | Checksum or size mismatch: CSV fails the job; JSON skips the file; neither retries | 19 |
| U-SRC-02 | Oversize CSV, oversize combined JSON, and oversize manifest fail with `SOURCE_TOO_LARGE` | 1 |
| U-SRC-03 | Missing source object is `SOURCE_NOT_FOUND` and permanent; an S3 timeout is temporary | 21 |
| U-SRC-04 | CSV filename date or exchange disagreeing with manifest inputs fails with `TRADE_DATE_MISMATCH` | 2.1 |
| U-OBX-01 | Security-details CloudEvent has the exact attribute and `data` shape in LLD 14.2; `subject` equals `isin/` plus the ISIN | 14.2 |
| U-OBX-03 | After commit, an event is sent immediately only when no older event is pending in its ordering group; a failed send leaves it pending and does not fail the caller | 23.4 |
| U-OBX-02 | Backoff grows to a 5-minute cap; the event ID and time are unchanged across retries | 21 |
| U-REV-01 | Error preview holds at most five entries; `hasMoreErrors` and `errorCount` are consistent | 16 |
| U-REV-02 | `rawValue` is truncated at 1,000 Unicode characters without splitting a character, with `rawValueTruncated` set; omitted when there is no source value | 16 |
| U-REV-03 | Page token round-trips; a tampered token is a `400` | 16 |
| U-REV-04 | Current rating per agency is the `CURRENT` row with the latest rating date; ties and missing dates fall back to the most recently recorded | 13.6 |
| U-REV-05 | Collateral assets are all returned when `Secured` and none when `Unsecured` | 15.2 |
| U-REV-06 | No response model has a field that can carry an S3 bucket, key, version, or source URL | 16 |

### Architecture

| ID | Case |
|---|---|
| U-ARCH-01 | `domain` packages import no Spring, AWS SDK, or JDBC types |
| U-ARCH-02 | No feature depends on another feature's adapters; no package cycles |
| U-ARCH-03 | Production code has no floating-point fields, signatures, or conversions, and reads the system clock or generates random UUIDs only in `shared.supplier` (every `java.time` `now()` without a `Clock`, `Clock.system*`, `System.currentTimeMillis`, `new Date()`, `UUID.randomUUID()`) |
| U-ARCH-04 | Every package is `@NullMarked`; Lambda and API Gateway types appear only in `lambda` and adapter packages |

## Integration test cases

### Database and migrations

| ID | Case | LLD |
|---|---|---|
| I-DB-01 | Flyway migrates an empty PostgreSQL 16 database; both schemas and all twelve tables exist with the columns in LLD 22 | 22 |
| I-DB-02 | Append-only unique indexes treat nulls as equal: inserting the same cash flow twice with a null field stores one row | 13.4, 22.1 |
| I-DB-03 | Numeric equality: `89400` and `89400.00` conflict in the unique index | 13.6 |
| I-DB-04 | Deleting a security with child rows is refused | 17.3 |

### Admission API

| ID | Case | LLD |
|---|---|---|
| I-ADM-01 | `POST /v1/event-ingestions` returns `202` with a receipt, `Location`, and a persisted request, pinned contracts, and outbox row in one transaction | 2.1 |
| I-ADM-02 | Replay returns the same receipt with `202`, even after the job is terminal; no new run is created | 2.1 |
| I-ADM-03 | Key reuse with a different payload returns `409` problem+json | 2.1 |
| I-ADM-04 | Wrong content type `415`, oversize body `413`, malformed envelope `400`; nothing is persisted | 2.1 |
| I-ADM-05 | Concurrent identical submissions produce one request | 2.1 |
| I-ADM-06 | Every documented response validates against `data-processing-service-openapi.yaml` | 2.1 |
| I-ADM-07 | Terraform test: the `POST /v1/event-ingestions` route has IAM authorization and every `GET` route has none | 23.2 |

### CSV end to end

| ID | Case | LLD |
|---|---|---|
| I-CSV-01 | Golden bhavcopy: submission to `COMPLETED`; summaries stored with face value; canonical JSONL written to the canonical bucket; ISIN-only securities created | 3, 14 |
| I-CSV-02 | File with invalid and duplicate rows ends `COMPLETED_WITH_ERRORS`; rejected records and issues stored; accepted rows are not in PostgreSQL canonical storage | 17.5 |
| I-CSV-03 | Malformed late record: job `FAILED`; no summary changed | 3 |
| I-CSV-04 | Resubmission with a new key upserts existing keys, overwrites with nulls, and leaves absent ISINs unchanged | 7 |
| I-CSV-05 | Failure injected midway through publication rolls back every summary, security, and outbox row of that file | 8.1 |
| I-CSV-06 | Two jobs for one trade date run in acceptance order even when the first is retried | 8.2 |
| I-CSV-07 | Temporary S3 failure then success: one request, two runs, counts and errors from the final run only | 8.3, 16 |
| I-CSV-08 | Three temporary failures: job `FAILED`, message in the dead-letter queue, next job for the date proceeds | 8.3, 21 |

### New-security event

| ID | Case | LLD |
|---|---|---|
| I-EVT-01 | First accepted trade for a new ISIN creates the security and one outbox event in the publication transaction; the event reaches the security-details queue with `contentType` attribute | 14 |
| I-EVT-02 | A later trade for an existing ISIN, including an ISIN-only one, creates no event | 14.3 |
| I-EVT-03 | Two jobs introducing the same ISIN concurrently produce one security and one event | 14.3 |
| I-EVT-04 | A rejected row creates no security and no event | 14.1 |
| I-EVT-05 | SQS unavailable after commit: the event stays pending and is delivered later with the same ID and time | 14.2 |

### JSON end to end

| ID | Case | LLD |
|---|---|---|
| I-JSON-01 | Six-file golden manifest for one ISIN enriches the ISIN-only security; collections stored; redemptions file contributes nothing | 13 |
| I-JSON-02 | Resubmitting the same data changes nothing: no `updated_at` change, no new collection rows, job `COMPLETED` with zero changes | 13.5, 13.8 |
| I-JSON-03 | A later submission with one changed scalar updates that field, its source reference, and `updated_at` only | 13.5 |
| I-JSON-04 | One malformed file and one checksum mismatch are skipped; the rest are applied; job `COMPLETED_WITH_ERRORS` | 13.7, 19 |
| I-JSON-05 | Publication failure rolls back all scalar and collection changes of the request | 13.7 |
| I-JSON-06 | Two requests for one ISIN run in acceptance order; requests for different ISINs are independent | 13.5 |
| I-JSON-07 | `Unsecured` after `Secured` clears coverage on the security and keeps historical asset rows | 15.2 |
| I-JSON-08 | JSON for an ISIN with existing daily summaries leaves those summaries untouched | 14.1 |

### Read APIs

| ID | Case | LLD |
|---|---|---|
| I-READ-01 | Job status for queued, running, completed, completed-with-errors, and failed jobs; CSV and JSON `counts` shapes | 20.3 |
| I-READ-02 | Error list: 120 errors page as 50, 50, 20 in stable order; `isin` filter is trimmed and uppercased | 16 |
| I-READ-03 | Security view for a full security, an ISIN-only security, and an unknown ISIN (`404`) | 20.1 |
| I-READ-04 | Per-ISIN summaries: newest first, inclusive date range, paging, unknown ISIN `404`, known ISIN with none returns empty `items` | 20.2 |
| I-READ-05 | Per-date summaries: required `tradeDate`, optional `exchangeName`, ordered by ISIN, `400` on a bad date | 20.2 |
| I-READ-06 | Every documented response validates against `data-processing-service-read-openapi.yaml` | 20 |
| I-READ-07 | No read response body contains a bucket name, object key, or `source_url` present in the test data | 16 |
| I-READ-08 | Decimals are serialised as exact JSON numbers; timestamps are UTC | 20.1 |

### Operations

| ID | Case | LLD |
|---|---|---|
| I-OPS-01 | Retention job deletes rejected records and issues older than one year and delivered outbox events older than 30 days; nothing else | 21.1 |
| I-OPS-02 | After cleanup, job status still reports stored counts and an empty error list | 21.1 |
| I-OPS-03 | Invocation dies mid-run: the message becomes visible, a new run starts, and no partial business data exists | 8.1, 23.3 |
| I-OPS-04 | Sweeper delivers pending outbox rows once and in order, and fails a job stuck past its final attempt limit | 23.3, 23.4 |
| I-OPS-05 | Each Lambda handler processes its fixture event end to end (API Gateway, SQS, schedule) and emits the agreed log fields and metrics | 23.1 |
| I-OPS-06 | Jobs in two different ordering groups run concurrently; two jobs in one group never overlap | 23.3 |

## Smoke test cases (final release workflow only)

| ID | Case |
|---|---|
| S-01 | Deployed read API answers over HTTPS at `processing.kagent.app`; an unknown ISIN returns `404` problem+json |
| S-02 | An unsigned `POST /v1/event-ingestions` is rejected with `403` by API Gateway |
| S-03 | A real BSE manifest submitted by the fetch service with a signed request is accepted and reaches a terminal status |
| S-04 | A new ISIN from S-03 produces a security-details event; the fetch service's NSDL manifest for it is processed and the security view shows enriched fields |
| S-05 | Canonical file for the run exists in the canonical bucket |
