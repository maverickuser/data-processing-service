# Structured File Processing — Low-Level Design

Status: design closed for implementation planning. All open questions were resolved on 2026-10-02.
Updated: 2026-10-02.
Source specification: [structured-file-processing-spec.md](structured-file-processing-spec.md).

This document records the CSV and JSON processing rules. Both formats are submitted through an S3 manifest, as confirmed against the fetch-service LLD and implementation plan. Sections 2–11 describe CSV unless explicitly stated otherwise; section 13 defines JSON-specific behavior. Section 15 records internal field names; section 17 records agreed schemas and business-table names. The submission API is defined in the OpenAPI 3.1 contract. Read endpoints are public and unauthenticated; POST requires AWS IAM authorization at API Gateway (section 23). Read APIs are in section 20 and [data-processing-service-read-openapi.yaml](data-processing-service-read-openapi.yaml); operational defaults, retention, and sizing in section 21; table definitions in section 22. Where an earlier section and sections 17.5 or 20–23 differ, the later section is authoritative; in particular section 23 replaces every earlier reference to ECS, containers, load balancers, listeners, and a polling dispatcher. The standalone YAML contract files in [`contracts/`](../../contracts) are authoritative over the illustrative snippets in section 6.

## 1. Scope and agreed technology

- Java with Spring Boot, running on AWS Lambda behind Amazon API Gateway (revised 2026-10-02; this supersedes the earlier ECS Fargate and load-balancer design). Section 23 is authoritative for the runtime architecture.
- The worker is an SQS-triggered Lambda function, without Spring Batch. Application processing records govern execution, attempts, recovery, and the agreed transaction boundaries; do not add Spring Batch metadata tables or its job repository.
- Amazon SQS for asynchronous processing.
- The outbound security-details SQS queue is externally provisioned. Its ARN and queue URL are supplied to this service; this project's Terraform does not create, import/manage, or destroy that queue. Internal file-processing queue ownership is a separate concern.
- PostgreSQL on Amazon RDS for jobs, rejected records, validation issues, outbox events, and accepted securities data. Complete canonical run output is stored in the service's own S3 bucket (section 17.5).
- One database with two agreed schemas: `securities_data` for accepted securities-domain data and `data_processing` for jobs, rejected records, validation issues, and delivery bookkeeping. See section 17 for table names and section 22 for definitions.
- Repository: `https://github.com/maverickuser/data-processing-service`. GitHub Actions provides CI and CD; Terraform manages service-owned infrastructure. Service documents, contracts, and code live in that repository; the implementation plan is [docs/plans/data-processing-service-implementation-plan.md](../plans/data-processing-service-implementation-plan.md). Adapt the fetch-service engineering guidance to Java/Spring Boot rather than adopting its Go/Lambda architecture or language-specific commands.
- AWS deployment region is `ap-south-1`, supplied by the GitHub Actions configuration variable `aws_region` (`${{ vars.aws_region }}`). Pass it to Terraform as `TF_VAR_aws_region` and to AWS authentication/SDK configuration consistently; do not hardcode the region in application logic.
- Deploy one environment, `prod`, initially. Do not provision separate development or staging environments. Persistent and disposable Terraform roots/states both belong to this production environment; lifecycle separation does not imply multiple deployment environments.
- Lambda functions and RDS run in private subnets of the shared VPC. Public read-only APIs and the submission route are served by one API Gateway HTTP API. Read routes are public and unauthenticated. `POST /v1/event-ingestions` requires AWS IAM authorization (SigV4) and only the fetch service's delivery role is permitted to invoke it; there are no load balancers. Authentication for the public read surface may be added later. AWS IAM roles govern service access to S3, SQS, and other AWS resources. See section 23.
- Shared-VPC enforcement: the separate repository `cloud-platform-network` (https://github.com/maverickuser/cloud-platform-network) is the sole owner of the VPC, subnets, route tables, NAT gateway, VPC endpoints, and the per-service Lambda security groups, in its own Terraform state. Both this service and data-fetch-service call its reusable workflow first in their deployment and read its outputs. Neither application stack may create a second VPC or accept independently configured subnet IDs.
- Agreed API hostname: `processing.kagent.app`. The existing public Route 53 hosted zone for `kagent.app` is shared with another application. Reference that existing zone (prefer an explicitly supplied hosted-zone ID); do not create or assume Terraform ownership of the zone. The hostname is an API Gateway custom domain. Manage only this service's subdomain alias and its own certificate-validation records. Preserve apex records and all other applications' records during deployment and teardown. Application authentication is disabled initially; detailed public/private DNS records remain to be finalized; the shared-VPC and private-submission network contract is fixed above.
- Application teardown must preserve PostgreSQL, including accepted securities data, processing jobs, rejected records, and outbox records; the canonical-file S3 bucket is preserved the same way. Manage the database and the supporting resources it depends on through a separate persistent Terraform root/state and lifecycle, outside the disposable application's destroy workflow.
- Versioned YAML source-validation and mapping contracts in Git. Java implements a fixed set of referenced rules; YAML contains no executable code or expressions.
- Initially approximately 10 CSV files per day, each no larger than 10 MB. A JSON manifest request has a combined 10 MB limit across its listed JSON files. Agreed: 10 MB means 10,485,760 bytes (10 × 1024 × 1024) of S3 object size, for one CSV or for the listed JSON files combined; the manifest object itself is limited to 1,048,576 bytes. Exceeding a limit makes the job `FAILED` with `SOURCE_TOO_LARGE` and no business-data changes.
- Processing runs in parallel across ordering groups and strictly one at a time within a group (one trade date, or one ISIN), with worker concurrency capped at 10 (section 23). The upstream service plans for bursts of roughly 10,000 NSDL requests per day initially.

An upstream service downloads, verifies, and uploads each original file to S3. It calls this service only after the object is complete and ready. Successfully uploaded source objects are not subsequently overwritten. The upstream service runs in the same AWS account.

This service does not upload or replace original files. A submission references a completed S3 manifest: BSE lists one extracted CSV; NSDL lists the complete downloaded JSON set for one ISIN. Process exactly the listed files, even when they occupy different subfolders. Do not discover processing inputs by scanning a prefix. This supersedes the earlier direct CSV-object and JSON-prefix submission designs.

## 2. Input and output

### 2.1 Submission

The submission follows section 11 of [the fetch-service LLD](https://github.com/maverickuser/data-fetch-service/blob/main/docs/specs/data-puller-service-lld.md#11-manifest-fingerprint-and-processor-contract), corroborated by PRs 06–07 of [its implementation plan](https://github.com/maverickuser/data-fetch-service/blob/main/docs/plans/data-fetch-service-implementation-plan.md). It includes an `Idempotency-Key` header and the S3 location of the complete event manifest. External fetch-service field names retain their defined snake_case spelling.

The object at `data.manifest.bucket`/`data.manifest.key` is a CloudEvents 1.0 event. The CloudEvent `type` identifies the manifest envelope (`com.bondplatform.dataset.manifest.v1`). The CloudEvent `dataschema` is the stable dataset contract URN used by the processor: `urn:bond-platform:dataset:nsdl-security` or `urn:bond-platform:dataset:bse-debt-trades`. These v1 URNs are immutable; an incompatible contract revision gets a new URN rather than changing the meaning of an existing one. The processor rejects an unknown dataset URN or a mismatch between `dataschema` and the manifest's `data.event_type`/file set.

```http
POST /v1/event-ingestions
Content-Type: application/cloudevents+json
Idempotency-Key: run_202

{
  "specversion": "1.0",
  "id": "urn:bond-platform:submission:run_202",
  "source": "urn:bond-platform:service:data-fetch-service",
  "type": "com.bondplatform.dataset.manifest.v1",
  "dataschema": "urn:bond-platform:dataset:nsdl-security",
  "time": "2026-09-27T14:30:00Z",
  "subject": "isin/INE121A07QY9",
  "datacontenttype": "application/json",
  "data": {
    "schema_version": 1,
    "event_type": "nsdl-bond-data",
    "event_id": "evt_nsdl",
    "run_id": "run_202",
    "inputs": {
      "isin_code": "INE121A07QY9"
    },
    "manifest": {
      "bucket": "data-fetch-service-artifacts",
      "key": "runs/run_202/manifest.json"
    },
    "dataset_fingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

The formal upstream request/response/error contract is [data-processing-service-openapi.yaml](data-processing-service-openapi.yaml), also available as [JSON](data-processing-service-openapi.json) (OpenAPI 3.1). Both serializations must remain equivalent. HTTP carries the complete CloudEvent with `data.manifest`; the immutable S3 manifest retains the previously agreed CloudEvent with `data.files`. Distinguish their IDs: `urn:bond-platform:submission:{run_id}` for HTTP and `urn:bond-platform:manifest:{run_id}` for the S3 event. A `(source, id)` pair must never identify two different payloads. Retain the same HTTP event, including `time`, across retries. The HTTP reference is `data.manifest.bucket/key`, optionally pinned by `version_id`; it is never a prefix. Dataset URN, source, type, subject, schema version, fetch event ID/type, run ID, inputs, and fingerprint must agree between submission and stored manifest. `job_id` never determines which JSON fields are extracted: the one dataset contract applies all configured paths present in every file.

Admission validates the HTTP envelope and idempotency, then atomically persists the job, immutable admission receipt, pinned processing contracts, and dispatch intent before returning `202`. S3 reads and manifest/file checks run asynchronously. Failures discovered there appear in job status/errors, not as a later HTTP rejection. HTTP request limit is 65536 UTF-8 bytes, separate from source-data limits. Responses use processor camelCase; upstream event data retains snake_case. Replays return the same receipt with admission status `ACCEPTED`, even after processing ends; the status URL provides current state. `Location` and `statusUrl` point to the public read-only job resource. HTTP errors use RFC 9457 `application/problem+json`; see the OpenAPI document for 400/409/413/415/429/500/503 responses and retry rules. Read APIs carry no authentication. POST requires AWS IAM authorization (SigV4) at API Gateway, permitted only to the fetch service's delivery role (section 23). Read APIs remain public and unauthenticated.


- A durably accepted new job returns `202 Accepted` and a job ID.
- The fetch service considers the handoff complete after durable HTTP 202 admission. It does not poll or await a processing-completion callback. Status/error APIs remain available for users and other consumers.
- The same idempotency key with the same semantic payload returns the existing job with HTTP 202, including terminal jobs; it does not create a new processing attempt. Do not return 200 on admission replay because the fetch service accepts only 202.
- Reusing a key with a different payload returns `409 Conflict`.
- An intentional corrected submission uses a new key and creates a new job.
- CSV manifests require `inputs.exchangeName` and `inputs.tradeDate`. Apply these values to every trade in the listed file and cross-check them against the filename. They are manifest metadata, not additional selected CSV columns.
- Filename `BSE_fgroup01012026.csv` carries `2026-01-01` in `DDMMYYYY` format. Its date must match `tradeDate`; mismatch rejects the job.
- The manifest supplies the authoritative `tradeDate` in ISO `YYYY-MM-DD` format, derived by the fetch service from its already resolved logical run date. For example, `inputs: {"exchangeName": "BSE", "tradeDate": "2026-01-01"}` must agree with `BSE_fgroup01012026.csv`. Do not infer either required manifest value solely from the filename or current worker time. A mismatch rejects processing without business-data changes; precise exchange normalization remains part of contract validation.
- Agreed: the CSV basename must match `{exchangeName}_...{DDMMYYYY}.csv` — the exchange is the text before the first underscore and the date is the eight digits immediately before `.csv`. A filename with no exchange prefix, no eight-digit date, or an impossible calendar date makes the job `FAILED` with `INVALID_SOURCE_FILENAME` and no business-data changes.

Submission request, response and error schemas are defined in the linked OpenAPI document; read API schemas are in section 20. The agreed access split is: read-only endpoints are public and unauthenticated initially; processing submission requires AWS IAM authorization and is granted only to the fetch service (section 23). The public surface must not accept S3 locations, idempotency-key submissions, or any operation that changes business data.

### 2.2 Selected columns and mapping

All ten selected headers must exist (`FACE VALUE` was added on 2026-10-02; wherever this document still says nine selected columns, read ten). Only ISIN must contain a nonblank value in every row.

| Source | Internal field | Java value | PostgreSQL value | Required value |
|---|---|---|---|---|
| Manifest `inputs.tradeDate` | `tradeDate` | `LocalDate` | `DATE` | Yes |
| Manifest `inputs.exchangeName` | `exchangeName` | `String` | Text | Yes |
| `Security_cd` | `securityCode` | `String` | Text | No |
| `Open Price` | `openPrice` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `High Price` | `highPrice` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `Low Price` | `lowPrice` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `Close Price` | `closePrice` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `Total Traded Volume` | `tradedVolume` | `BigInteger` | Integer-valued `NUMERIC` | No |
| `Number of Trades` | `numberOfTrades` | `BigInteger` | Integer-valued `NUMERIC` | No |
| `Total Turnover` | `turnover` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `FACE VALUE` | `faceValue` | `BigDecimal` | `NUMERIC` without declared scale | No |
| `ISIN No.` | `isin` | `String` | Text | Yes |

`BigInteger` is proposed to avoid inventing a business range limit for counts. Database numeric types still have finite technical limits; an unrepresentable value must fail explicitly, never round or truncate silently.

The three unselected columns in the sample (`sc_name`, `COUP0N (%)`, `Maturity Date`) and any future additional columns are ignored for field validation and mapping. They remain in the unchanged original file in S3. Canonical field data contains only the ten selected columns.

## 3. Processing flow

```text
Receive manifest reference, event metadata, and idempotency key
  -> Validate admission envelope
  -> Durably accept job and arrange queue delivery
  -> Return HTTP 202 and job ID
  -> Load and persist required manifest exchangeName and tradeDate
  -> Cross-check the selected CSV filename exchange and date
  -> Process jobs in submission order for each trading date
  -> Read source object and enforce size limit
  -> Parse the entire CSV structure and validate headers
  -> Create canonical records for selected fields
  -> Normalize, parse, and collect all applicable validation errors
  -> Select the last valid row for each normalized ISIN
  -> Preserve quarantine outcomes and canonical lineage
  -> Upsert all accepted trades in one database transaction
  -> Publish terminal status and counts
```

No trade is updated until the full file has been checked. A malformed row late in the file must not leave earlier trade updates committed.

Files for the same trading date run one at a time in submission order, including retries. A job that exhausts its attempts becomes `FAILED` and releases later jobs for that date.

## 4. Canonical model

### 4.1 Logical entities

These are conceptual entities; tables are in sections 17 and 22. Canonical rows are written to the run's S3 canonical file, and only rejected rows are also stored in PostgreSQL (section 17.5).

| Entity | Responsibility |
|---|---|
| Job | Request identity, trade date, source reference, pinned contract versions, status, counters, ordering, and timestamps |
| Processing attempt | Attempt number, execution status, transient failure details, and timestamps |
| Canonical row | Selected source fields, record location, normalization/parsing results, validation errors, and final disposition |
| Current trade | Latest accepted values for `(isin, tradeDate, exchangeName)` and a reference to the supplying canonical row |

Committed canonical history is retained across processing attempts and intentional resubmissions. A later submission does not rewrite previous canonical evidence. An attempt's records are finalized before publication and are not reused as mutable source records by later attempts.

### 4.2 Canonical row example

For readability this example shows one of the nine selected fields. A complete canonical row contains all nine, including blank optional fields.

```json
{
  "canonicalRowId": "row-123",
  "jobId": "job-123",
  "attemptNumber": 1,
  "tradeDate": "2026-01-01",
  "source": {
    "bucket": "source-bucket",
    "key": "DEBTBHAVCOPY01012026/BSE_fgroup01012026.csv",
    "recordNumber": 2
  },
  "sourceContractVersion": "fgroup-csv-v1",
  "mappingContractVersion": "fgroup-trade-v1",
  "fields": {
    "close_price": {
      "sourceHeader": "Close Price",
      "columnIndex": 6,
      "rawValue": " 1,234.5600 ",
      "normalizedValue": "1234.5600",
      "parsedValue": "1234.5600",
      "dataType": "DECIMAL",
      "validationStatus": "PASSED",
      "errors": []
    }
  },
  "rowValidationStatus": "PASSED",
  "disposition": "ACCEPTED",
  "rowErrors": []
}
```

Record numbers are one-based logical CSV record numbers, including the header as record 1. Column indexes are one-based and follow the actual file order. A CSV record can span physical lines; physical start/end line information may also be recorded if the parser supplies it.

`rawValue` is the decoded cell string before normalization, not an exact slice of CSV syntax: quoting and escaping are handled by the CSV parser. Exact source bytes remain in S3.

Proposed durable canonical representation: decimal and integer parsed values are stored as strings with an explicit `dataType`, avoiding precision loss through generic JSON handling. Java reconstructs exact typed values from them. This is separate from API serialization: decimal API values are JSON numbers, as agreed. No server-side floating-point conversion or rounding is allowed.

| Case | Parsed value | Field status | Error |
|---|---|---|---|
| Valid populated field | Typed value, serialized according to canonical convention | `PASSED` | None |
| Blank optional field | `null` | `PASSED` | None |
| Blank ISIN | `null` | `FAILED` | `REQUIRED_VALUE_MISSING` |
| Unparseable numeric value | `null` | `FAILED` | Parsing error |
| Parsable but negative number | Preserve parsed number | `FAILED` | `NEGATIVE_VALUE` |

An invalid field's raw value is always retained. A negative value is parsable, so retaining its typed value distinguishes parsing failure from constraint failure.

### 4.3 Row dispositions

- `ACCEPTED`: row passes validation and is the last valid occurrence of its normalized ISIN.
- `QUARANTINED_INVALID`: at least one applicable field or row-level rule fails.
- `QUARANTINED_SUPERSEDED`: row passes validation, but a later valid row has the same normalized ISIN.

A structurally rejected file has no accepted rows. Partial diagnostic records, if persisted before structural failure is detected, must not be presented as completed canonical output eligible for mapping.

## 5. Validation and normalization

### 5.1 File-level rules

- Enforce the configured maximum source size.
- Reject an empty file or a header-only file.
- Reject malformed CSV, including broken quoting or a data record whose cell count differs from the full header count, including unselected columns.
- Trim surrounding header whitespace and compare headers case-insensitively using locale-independent normalization.
- Reject duplicate headers after normalization, including duplicate unselected headers.
- Require all ten selected headers; permit reordering and additional headers.
- Ignore additional columns for value validation, while still checking overall CSV structure.
- Reject a filename/trading-date mismatch.

Agreed parser settings: CSV and JSON are UTF-8; a leading UTF-8 byte-order mark is accepted and ignored. CSV follows RFC 4180 (comma delimiter, double-quote quoting, doubled quote as escape, CRLF or LF record ends). Fully blank physical lines, including lines of only whitespace, are skipped; they are not records, are not counted, and are not errors. A stray double quote inside an unquoted cell is kept as part of the cell text and fails only that field's validation, not the whole file. Bytes that are not valid UTF-8 make a CSV `MALFORMED_CSV` and a JSON file malformed (skipped with an error). Embedded grouping commas must be correctly quoted by the source CSV.

### 5.2 Field-level rules

Normalization never mutates the stored raw string.

1. Trim surrounding field whitespace.
2. Convert empty optional fields to `null`; whitespace-only ISIN fails required validation.
3. Normalize ISIN to uppercase. Perform no ISIN length, format, or check-digit validation in v1.
4. Preserve security code as text, including leading zeros.
5. For populated numeric fields, accept ungrouped numbers or valid Western/Indian comma grouping, with `.` as decimal separator.
6. Validate grouping before removing commas. Do not blindly strip malformed separators.
7. Parse exactly; never use `float` or `double` as an intermediate.
8. Reject negative prices, volume, number of trades, and turnover; allow zero.
9. Require mathematical whole numbers for traded volume and number of trades. For example, `14.00` becomes integer `14`; `14.50` fails.
10. Do not impose a fixed fractional scale on prices or turnover; do not round their values.

Examples of accepted grouped numeric text: `1,234.56`, `1,23,456.78`. Decimal-comma interpretation is not enabled. Scientific notation, currency symbols, parentheses for negatives, and other formats have not been agreed; the proposed v1 grammar excludes them.

### 5.3 Cross-field price checks

Run each comparison only when both participating fields are populated and individually valid:

- `highPrice >= lowPrice`
- `openPrice >= lowPrice`
- `openPrice <= highPrice`
- `closePrice >= lowPrice`
- `closePrice <= highPrice`

Collect every applicable error rather than stopping at the first. Do not emit dependent comparison errors when an input cannot be parsed or has already failed its own constraints.

### 5.4 Duplicate selection

Duplicate identity is the normalized ISIN within this file. All rows receive their own validation results before winner selection.

- Select the last individually valid row in file order for each ISIN.
- Earlier valid rows for that ISIN become `QUARANTINED_SUPERSEDED` with `DUPLICATE_ISIN_SUPERSEDED` and a reference to the winning record number.
- Invalid occurrences retain their validation errors and never displace a valid occurrence.
- If no occurrence is valid, no trade is produced for that ISIN.

Example: row 2 valid, row 5 valid, row 9 invalid for the same ISIN results in row 5 accepted, row 2 superseded, and row 9 invalid.

## 6. Proposed YAML contracts

The YAML below is the original illustration and is superseded by the contract files in `contracts/` (2026-10-02). Those files hold only the keys the service reads: fields, types, named rules, row rules, the duplicate key, size limits, JSON paths, and mappings. Behaviour that is the same for every dataset (CSV dialect and header matching, number format, row-rule execution, duplicate winner selection, presence handling, persistence semantics) is fixed in code and specified in sections 5, 7, and 13 rather than repeated as configuration, and a contract containing any other key fails startup. Field types are named `text`, `decimal`, `integer`, `date`, and `percent`.

These examples define a small application-specific declarative format. Java must validate the configuration and reject unknown rules, conflicting mappings, or invalid rule parameters at startup.

### 6.1 Source contract

```yaml
id: fgroup-csv
version: v1
format: csv

headers:
  normalize: [trim, caseInsensitive]
  duplicates: rejectFile
  additionalColumns: ignore
  selectedHeadersRequired: true

fields:
  security_code:
    header: Security_cd
    type: string
    requiredValue: false
    normalize: [trim, blankToNull]
  open_price:
    header: Open Price
    type: decimal
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  high_price:
    header: High Price
    type: decimal
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  low_price:
    header: Low Price
    type: decimal
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  close_price:
    header: Close Price
    type: decimal
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  traded_volume:
    header: Total Traded Volume
    type: integer
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  number_of_trades:
    header: Number of Trades
    type: integer
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  turnover:
    header: Total Turnover
    type: decimal
    requiredValue: false
    normalize: [trim, blankToNull, normalizeGroupedNumber]
    validate: [nonNegative]
  isin:
    header: ISIN No.
    type: string
    requiredValue: true
    normalize: [trim, blankToNull, uppercase]

numericFormat:
  decimalSeparator: '.'
  groupingSeparator: ','
  acceptedGrouping: [western, indian]
  validateGroupingBeforeRemoval: true
  rounding: forbidden
  integerParsing: exactMathematicalInteger

rowRules:
  - rule: greaterThanOrEqual
    left: high_price
    right: low_price
  - rule: greaterThanOrEqual
    left: open_price
    right: low_price
  - rule: lessThanOrEqual
    left: open_price
    right: high_price
  - rule: greaterThanOrEqual
    left: close_price
    right: low_price
  - rule: lessThanOrEqual
    left: close_price
    right: high_price

rowRuleExecution:
  requirePresentAndValidOperands: true
  collectAllErrors: true

duplicates:
  key: isin
  winner: lastValidRow
  otherValidRows: quarantineSuperseded
```

Required-value checks run after trim/blank normalization and before optional-null short-circuiting. Nonblank inputs must be parsed successfully before their typed constraints run. Integer parsing must use an exact conversion, not truncation.

### 6.2 Mapping contract

```yaml
id: fgroup-trade
version: v1
sourceContract: fgroup-csv-v1
target: Trade
eligibleDisposition: ACCEPTED

contextMappings:
  tradeDate: tradeDate
  exchangeName: exchangeName

fieldMappings:
  security_code: securityCode
  open_price: openPrice
  high_price: highPrice
  low_price: lowPrice
  close_price: closePrice
  traded_volume: tradedVolume
  number_of_trades: numberOfTrades
  turnover: turnover
  isin: isin

persistence:
  key: [isin, tradeDate, exchangeName]
  action: upsert
  onMatch: replaceAllMappedValues
  optionalNulls: overwriteExistingValues
  missingIsins: leaveExistingTradesUnchanged

lineage:
  canonicalRowReference: required
```

The mapper consumes validated typed canonical fields, not raw CSV strings, and does not perform additional hidden normalization. Contract IDs and versions are pinned when the job is accepted and must remain available across retries and deployments. Store a contract content hash as an implementation safeguard against changing an existing version in place.

## 7. Internal trade semantics

Example internal object:

```json
{
  "tradeDate": "2026-01-01",
  "exchangeName": "BSE",
  "securityCode": "976009",
  "openPrice": 114200.00,
  "highPrice": 114200.00,
  "lowPrice": 114200.00,
  "closePrice": 114200.00,
  "tradedVolume": 14,
  "numberOfTrades": 1,
  "turnover": 1598800.00,
  "isin": "INE0KH208019",
  "canonicalRowId": "row-123"
}
```

- A database uniqueness constraint enforces `(isin, tradeDate, exchangeName)`.
- A new valid record inserts or replaces all mapped values for that key.
- Optional nulls overwrite previous values; this is not a patch/merge operation.
- Invalid or superseded records never change the current trade.
- ISINs absent from the new submission remain unchanged.
- Repeating the same values leaves the business state equivalent, although an intentional new submission has distinct history and may update lineage.
- The origin reference (job ID, source filename, record number — `canonicalRowId` in the examples above denotes this reference) plus the pinned mapping contract resolves field-level lineage without copying every raw value into the trade object. `tradeDate` and `exchangeName` trace to manifest inputs, cross-checked against the filename.

## 8. Atomic publication, retry, and ordering

### 8.1 Transaction boundary

Rejected-record evidence may be persisted in bounded batches, and the canonical file written to S3, before trade publication. It is associated with an attempt and is not treated as published trade data.

After complete structural validation, row validation, and duplicate selection:

1. Build the accepted set from finalized canonical outcomes.
2. If the set is empty, finalize the job as `FAILED`; leave trades unchanged.
3. Otherwise begin one database transaction for the whole accepted set.
4. Ensure each accepted ISIN has a security record, creating an ISIN-only record when absent.
5. Upsert accepted trades and their canonical references.
6. Only for securities newly inserted by this accepted-trade transaction, durably record a security-details event in the same transaction, as described in section 14. Existing securities never trigger a new event through trade processing, even if they contain only ISIN.
7. Record the successful publication and final job status/counts in that same transaction.
8. Commit, then acknowledge the queue message. Dispatch committed security-details events asynchronously.

A transaction failure rolls back all trade updates for the file. Quarantine evidence already persisted remains available. A queue redelivery after a committed publication checks the durable terminal state and does not publish again.

### 8.2 Proposed reliable delivery design

SQS and PostgreSQL do not share a database transaction. Proposed implementation: persist the job and an outbox entry together, then use a dispatcher to deliver it to SQS. This avoids accepting a job in PostgreSQL but silently losing it before enqueueing.

Use SQS FIFO with a message group derived from the trading date to support the agreed ordering. A durable per-date submission sequence and ordered outbox dispatch define order; HTTP requests that arrive concurrently are ordered by that acceptance sequence. Queue deduplication alone does not replace API idempotency or durable job-state checks.

These queue/outbox details are implementation proposals to review with the operational design. They must preserve ordering during retries and must not let a delayed older job overwrite a newer committed submission.

### 8.3 Retry policy

- Three total processing attempts for temporary failures, such as transient S3/database unavailability.
- Validation/content failures do not trigger automatic retries.
- Quarantined rows do not trigger a retry of an otherwise completed job.
- Retrying a transient failure retains the same job and pinned contracts, with a new attempt identity when processing actually restarts.
- After attempts are exhausted, mark the job `FAILED` and let the next job for the same date proceed.
- A corrected intentional submission uses a new idempotency key and enters the queue as a new job.
- Retry delay, visibility timeout, and dead-letter handling are agreed in sections 21 and 23.

## 9. Job status and review

Proposed nonterminal statuses are `QUEUED`, `PROCESSING`, and `RETRY_PENDING`.

Agreed terminal outcomes:

| Outcome | Status |
|---|---|
| At least one accepted row, no quarantined rows | `COMPLETED` |
| At least one accepted row, at least one quarantined row | `COMPLETED_WITH_ERRORS` |
| No accepted rows, invalid file, or exhausted processing retries | `FAILED` |

Quarantine counts include superseded duplicate rows. Counts describe the final applicable attempt, not a sum across retries. For a structurally valid, completely evaluated file, accepted plus quarantined rows equals source data records. Malformed files may have incomplete counts and must report that clearly.

Review APIs will expose job/attempt identity, trade date, source record number, raw selected values, and field/row errors. Quarantine data is held in PostgreSQL; S3 export or archival is not required initially. Review endpoints are read-only; they do not directly correct data or resubmit jobs. A corrected input follows the internal submission API with a new idempotency key. Canonical history itself is not edited in place.

Illustrative validation error:

```json
{
  "code": "INVALID_DECIMAL",
  "field": "close_price",
  "sourceHeader": "Close Price",
  "recordNumber": 7,
  "rawValue": "abc",
  "message": "Expected a decimal with a dot decimal separator and optional valid comma grouping."
}
```

Proposed stable error codes include `REQUIRED_HEADER_MISSING`, `DUPLICATE_HEADER`, `MALFORMED_CSV`, `EMPTY_FILE`, `TRADE_DATE_MISMATCH`, `REQUIRED_VALUE_MISSING`, `INVALID_NUMBER_GROUPING`, `INVALID_DECIMAL`, `NOT_WHOLE_NUMBER`, `NEGATIVE_VALUE`, `PRICE_INCONSISTENT`, and `DUPLICATE_ISIN_SUPERSEDED`. JSON field validation adds `INVALID_DATE` (not a real date from 1900 onwards in an accepted format) and `INVALID_TYPE` (a JSON value of the wrong kind, such as a number where text is due). Numbers longer than 1,000 characters are `INVALID_DECIMAL`.

## 10. Component responsibilities

| Proposed Java component | Responsibility |
|---|---|
| Submission service | Validate request, enforce idempotency, pin contracts, persist job/outbox |
| Contract registry | Load and validate versioned YAML; resolve fixed Java rules |
| Outbox dispatcher | Reliably enqueue jobs in per-date order |
| Processing worker | Claim job, track attempts, orchestrate stages, acknowledge after durable outcome |
| S3 source reader | Read unchanged source with bounded size and source metadata |
| CSV canonicalizer | Parse structure, resolve headers, preserve selected raw cells and locations |
| Field normalizer/parser | Apply declared normalization and exact typed parsing |
| Validation engine | Required/type/constraint checks and conditional cross-field checks |
| Duplicate resolver | Choose last valid row per normalized ISIN |
| Trade mapper | Produce internal trades from accepted typed canonical rows |
| Trade publisher | Atomically upsert the accepted set and finalize publication |
| Review/status service | Read statuses, counts, canonical history, and quarantine errors |

Do not retain an entire file as a large object graph merely because the input limit is 10 MB; canonical objects can be much larger than source bytes. Stream parsing and persist bounded batches. Duplicate selection keeps, per normalized ISIN, only the last valid record seen while streaming; it does not depend on persisted rows. The atomic trade transaction can execute bounded write batches while remaining a single transaction.

## 11. Required verification scenarios

- Header normalization, reordering, missing selected headers, duplicate normalized headers, and additional columns.
- Optional blanks versus required ISIN; leading zeros in security code; uppercase/trimmed ISIN identity.
- Western and Indian grouped numeric values; malformed grouping; exact high-precision decimals without rounding.
- Fractional volume/count rejection, integer-valued decimal acceptance, negative rejection, and zero acceptance.
- Price comparisons with complete, partial, and invalid operands; collection of all applicable errors.
- Last-valid duplicate wins, including a later invalid occurrence.
- Partial success and all-quarantined outcomes, including correct duplicate quarantine counts.
- Entire-file rejection for a malformed late record, with no trade changes.
- Upsert of existing keys, null overwrites, absent ISIN preservation, and traceable canonical history.
- Transaction rollback on a mid-publication failure; queue redelivery after a successful commit.
- Idempotency replay versus conflicting payload, corrected submissions, and per-date ordering through retries.

## 12. Deferred decisions

All design decisions needed for implementation were closed on 2026-10-02. Deliberately out of v1 scope:

- Authentication for the public read APIs.
- Mapping of the NSDL redemptions file.
- Per-submission collection-membership tracking (which collateral assets or listings appear in the latest source).
- Downloadable error reports, review-correction workflow, and S3 export of rejected records.
- Manual refresh or re-fetch of security details for an existing ISIN.
- Multi-AZ database, second NAT gateway, and additional environments.

Java/Spring versions and the CSV parser library are implementation-plan choices.

## 13. JSON manifest processing

This section records agreed JSON behavior. Do not apply CSV row quarantine, trade-date identity, or null-overwrite behavior to JSON: JSON uses field-level quarantine, filename ISIN identity, and no-update semantics for nulls/placeholders.

### 13.1 Submission and source identity

- One request identifies an S3 manifest listing multiple JSON files for exactly one ISIN.
- Process the exact `files[]` entries. Files can be located under different subfolders, as in the fetch-service `runs/{run_id}/raw/{job_id}/{attempt}/` layout. Neither a shared parent folder nor a filename suffix determines inclusion. The earlier single-flat-prefix discovery requirement is superseded.
- No snapshot date is required in the request or filename. A date segment in the prefix is organizational metadata, not an effective business date or CSV trading date. Requests continue to apply in submission order; an older snapshot submitted later is not automatically rejected based on its date.
- The upstream service submits only after all files are completely uploaded. No files are added or changed afterward, including during retries.
- Extract ISIN from each filename before its first underscore, or before `.json` when no underscore exists. Use the basename, not directory names. Both `INE831R08076_ratings.json` and `INE831R08076.json` are accepted naming forms.
- Trim the extracted filename ISIN and uppercase it. A nonblank filename identifier is mandatory; no ISIN format or check-digit validation applies. The remaining filename is unrestricted and does not select field mappings.
- All filenames must identify the same ISIN. A mismatch makes the entire request invalid: record/log the error, mark it failed, and publish no business-data changes. Job/error bookkeeping can still be persisted.
- The filename-derived ISIN is authoritative. An ISIN inside a payload is optional; disagreement is logged as a warning and does not block processing or redirect data to another security.
- The combined size of JSON files must not exceed 10 MB.
- An empty manifest file list, or a JSON request listing no JSON files, fails without business-data changes.
- Select the processor-owned YAML contract from the CloudEvent `dataschema` URN. In v1, `urn:bond-platform:dataset:nsdl-security` maps to the single `nsdl-security-json-v1.yaml` contract and `urn:bond-platform:dataset:bse-debt-trades` maps to `bse-debt-bhavcopy-csv-v1.yaml`. `data.event_type` and each file's `job_id` are validated as neutral source metadata and used for routing/traceability; they do not select separate contracts. Filenames identify the ISIN or trade-date context only and never select field mappings. There is no per-file `processing_contract` field. Agreed (2026-10-02): processing is two-stage with two contracts per dataset. The dataset URN selects the stage-1 source-to-canonical contract (`nsdl-security-json-v1.yaml`, `bse-debt-bhavcopy-csv-v1.yaml`), which owns extraction, normalization, and validation into canonical records with snake_case canonical field names. Each has one stage-2 canonical-to-internal-model mapping contract (`nsdl-security-mapping-v1.yaml`, `bse-debt-bhavcopy-mapping-v1.yaml`) that names its `sourceContract`, consumes only validated canonical values, and owns targets, persistence policy, and lineage. Both contract versions are pinned at job admission. The standalone YAML files are authoritative over the illustrative snippets in section 6.
- All selected payload fields are optional. Unselected fields do not contribute mapped data.

Persist the supplied manifest and its source-file references; resolve `LastModified` and available version/checksum metadata from the objects for the previously agreed precedence rules. Reuse stable manifest inputs during retries. Do not add unlisted objects discovered under related prefixes. The manifest may list any number of JSON files, including multiple files that contribute to the same collection such as `security_cash_flows`; process each listed file with the single dataset contract selected by `dataschema`, using `job_id` only to preserve source traceability. Append/deduplicate normalized outputs under the existing ISIN rules. The filename ISIN must also equal the manifest's `inputs.isin_code` after the same normalization; a difference fails the request with `INVALID_SOURCE_FILENAME` (implementation choice 2026-10-03, as no separate code is defined). An empty normalized filename identifier cannot satisfy the mandatory source-identity requirement; agreed outcome: the whole request is `FAILED` with `INVALID_SOURCE_FILENAME` (for example `_ratings.json`) and no business-data changes, consistent with the mixed-ISIN rule.

### 13.2 Selected source paths

These paths identify the selected source data; internal names are in section 15 and the contract files.

| Source section | Selected paths |
|---|---|
| Issuer/security details | `$.isin`, `$.issuerName`, `$.issuerTypeOwner`, `$.instrumentType` |
| Instrument dates and face value | `$.instrumentsVo.instruments.allotmentDate`, `$.instrumentsVo.instruments.redemptionDate`, `$.instrumentsVo.instruments.issuePrice` |
| Asset cover | `$.instrumentsVo.assetCover.securedFlag`, `$.instrumentsVo.assetCover.assetCvge`, `$.instrumentsVo.assetCover.assetCvrPercent`; `$.instrumentsVo.assetCover.assetList[*]`: `assetType`, `securityDetails`, `remarks` |
| Coupon | `$.coupensVo.couponDetails.couponRate`, `$.coupensVo.couponDetails.couponType` |
| Payment schedule | Contract-defined paths, including the sample `$.coupensVo.cashFlowScheduleDetails.cashFlowSchedule[*]`: `cashFlowsEvent`, `recordDate`, `dueDate`, `amountPayable`, `paymentDate`, `newFaceVal` |
| Listing status | `$.listingStatus` |
| Listings | `$.listingDetails[*]`: `exchangeName`, `listingDate` |
| Current ratings | `$.currentRatings[*]`: `creditRatingAgencyName`, `currentRating`, `outlook`, `ratingAction`, `creditRatingDate`, `ratingChangeDate`, `dateOfVerification` |
| Earlier ratings | `$.earlierRatings[*]`: the same seven rating fields |

The main instrument allotment date is selected; the date in `furtherIssueVo` is not. Neither `interestCashFlowSchedule` nor `redemptionCashFlowSchedule` is selected. The top-level `instrumentRateFlag` and `earlierListingDetails` are not selected.

### 13.3 Relationships

- Filename ISIN identifies the security shared by the files.
- Selected scalar issuer, instrument, coupon, and listing-status data are 1:1 with that security.
- Payment schedules, listings, and ratings are 1:N with that security.
- Current versus earlier rating source membership is retained.
- CSV trades are also 1:N with the security, maintaining their existing `(isin, tradeDate, exchangeName)` unique key and CSV update rules.
- `newIsin` is excluded from selected canonical fields and the internal model. If supplied, it is ignored like other unselected fields and remains only in the original source evidence.

### 13.4 Presence, normalization, and field validation

Preserve source primitives and JSONPaths in canonical history, distinguishing missing fields, explicit nulls, placeholders, valid values, and invalid values.

| Incoming value | Business-data action |
|---|---|
| Missing path | No update |
| Explicit JSON `null` | No update |
| Blank/whitespace-only string, `-`, or `N.A.` | No update |
| Valid populated value | Eligible for scalar update or collection append |
| Invalid populated value | Quarantine only that field; process other valid fields |

- JSON paths and property names match exactly, including case.
- Selected text fields accept JSON strings only. Trim surrounding whitespace and preserve letter case.
- A number, boolean, object, or array supplied for a selected text field is a field validation error; do not silently coerce it to text.
- Dates accept strings in `DD-MM-YYYY` and `YYYY-MM-DD`. Validate calendar dates strictly, normalize to ISO format, and use database dates.
- Impossible dates or unrecognized date formats quarantine only that field.
- Coupon rate accepts JSON numbers or numeric strings, with or without a trailing `%`. Examples `"8.94%"`, `"8.94"`, and numeric `8.94` all represent 8.94 percentage points, not a fraction of 0.0894.
- The agreed coupon representation carries an explicit unit: `{ "value": 8.94, "unit": "PERCENT" }`. It is `couponRate` on the security (section 20.1).
- `amountPayable` accepts JSON numbers and strings using the CSV-agreed Western/Indian comma grouping and dot decimal separator. Validate grouping before removing commas.
- Parse numbers exactly without intermediate floating-point conversion or rounding.
- Negative coupon rate and amount payable values quarantine only that field; zero is valid.
- Do not apply an upper percentage bound or other unagreed business constraints.

For an invalid scalar value, leave existing accepted data unchanged. Inside a collection entry, retain its valid selected values and field errors separately. Skip the entry if no selected field has a valid nonblank value; retain any errors for review. Agreed collection-entry identity: within a collection entry, a missing field, explicit `null`, placeholder (blank, `-`, `N.A.`), and an invalid value are all persisted as SQL `NULL` in the business row, and two `NULL`s compare as equal for deduplication (`IS NOT DISTINCT FROM` semantics; a unique index must use `NULLS NOT DISTINCT` or an equivalent normalized hash). For example `{Interest, dueDate 08-06-2027, amountPayable "89,400.00", recordDate "-"}` and `{Interest, dueDate 08-06-2027, amountPayable 89400, paymentDate null}` are the same entry and one row is stored. The distinction between missing, null, placeholder, and invalid remains only in canonical evidence and recorded errors.

### 13.5 Multiple files and scalar updates

- A nonblank valid scalar changes the stored value only if its normalized value differs from the existing value.
- A changed value updates the affected record's timestamp; an identical value causes no business-row update and does not change that timestamp.
- Nulls, placeholders, missing fields, and invalid fields never erase a valid existing value.
- For competing valid nonblank values in one request, the object with the latest S3 `LastModified` wins. Full object key breaks timestamp ties consistently.
- Proposed deterministic tie convention: ascending `(LastModified, objectKey)` processing, with the last eligible value winning.
- These comparisons apply per scalar field: a newer file with no usable value for a field does not erase an earlier valid contribution.
- Across requests, process the same ISIN sequentially in submission order, including retries. This ordering is distinct from within-request S3 object precedence.

### 13.6 Append-only collections

- Payment schedule, listing, and rating entries are append-only.
- Deduplicate by filename ISIN, collection identity, and all selected normalized field values.
- An identical entry is skipped, including duplicates in one input, across its files, or from repeated submissions.
- A changed combination of selected values is a new entry; existing entries are neither overwritten nor deleted.
- Preserve rating collection identity so current and earlier provenance is not lost. The same values appearing in different source collections are not silently collapsed across collection identity.
- Missing, null, placeholder, or empty collection input does not delete accepted entries.
- Normalized comparison must treat equivalent numeric values consistently, for example numeric `89400` and string `"89,400.00"`, and equivalent accepted date formats consistently.
- A partially valid entry can be appended. If a subsequent submission supplies additional valid values, the resulting different tuple is appended rather than repairing the previous entry in place.

Append-only retention means an entry observed in `currentRatings` is not automatically removed when later data places it in `earlierRatings`. Agreed presentation of the current rating: it is derived at read time with no additional table or column. For each rating agency, the current rating is the `CURRENT`-category observation with the latest `rating_date`; when rating dates tie or are absent, the most recently recorded observation (`first_recorded_at`) wins. That rating remains current until a newer `CURRENT` observation for the same agency is recorded. All other observations are presented as history. An agency that no longer appears in later source data keeps its last current rating; absence is never treated as a withdrawal.

### 13.7 Execution, transaction, and failure handling

```text
Accept manifest request with idempotency key
  -> Load the manifest and establish its exact source-file list
  -> Validate total size and common filename ISIN before publication
  -> Read each JSON document
  -> Skip malformed JSON files with recorded errors
  -> Extract configured paths, preserving primitive types and JSONPaths
  -> Normalize and validate selected fields independently
  -> Resolve competing valid scalar values by S3 object precedence
  -> Form nonempty valid collection candidates and remove duplicates
  -> In one database transaction, apply changed scalars and new collection entries
  -> Finalize job status and change/error counts for polling
```

- Malformed JSON in one file skips that file and records an error; other files continue.
- Duplicate property names within the same JSON object make the file malformed; skip the file with an error instead of silently taking one value. The same property name in distinct objects is not a duplicate-property error.
- A structural type error quarantines the affected section while other valid sections continue. For example, an object supplied where `currentRatings` requires an array quarantines that collection. Record the failing JSONPath and expected/actual types; do not report an invalid structure merely as absent optional fields.
- Field validation errors quarantine only the field, not the file or entire ISIN.
- Mixed filename ISINs invalidate the whole request, irrespective of any otherwise valid content.
- Publish all valid changes for the request in one transaction; any publication failure rolls back the complete set of business-data changes.
- Retain source/canonical/error evidence independently for traceability and review, following the agreed canonical-history principle.
- Reuse API idempotency semantics, polling, and three total attempts for temporary failures from CSV. Content errors are not automatically retried.
- Serialize requests per ISIN; preserve ordering throughout retries. Proposed queue group identity must distinguish JSON ISIN groups from CSV trade-date groups.

### 13.8 Outcomes

| Evaluation outcome | Job status |
|---|---|
| Usable selected data, no validation/file errors | `COMPLETED` |
| Usable selected data with some field or file errors | `COMPLETED_WITH_ERRORS` |
| No usable selected data, invalid request, or exhausted temporary-failure attempts | `FAILED` |

Valid data already present unchanged counts as usable. Such a request succeeds with zero changes, or completes with errors if other fields/files fail. Payload ISIN mismatch is warning-only and does not by itself downgrade completion status.

Collection deduplication is normal successful behavior, not CSV-style duplicate quarantine. An input containing only unrecognized fields or no-update placeholders has no usable selected data. Counts for files, valid/invalid fields, skipped entries, changed scalars, and appended entries should be defined when the response/canonical model is finalized; do not reuse CSV row counts ambiguously.

### 13.9 Remaining JSON flow details and verification

No JSON flow details remain open: size limits are in section 1, partial collection-entry representation in section 13.4, and schemas in section 22.

Required verification includes:

- All selected paths from the five samples, independent of filename suffix; exact property casing and optional fields.
- Mixed filename ISIN rejection versus warning-only payload mismatch.
- Empty manifest, missing listed JSON files, combined size limit, and source-manifest stability across retries.
- Placeholder/no-update handling without erasing existing scalar values.
- Strict dates, exact numbers, percentage unit normalization, and text-type errors.
- Field-only quarantine, malformed-file skipping, and all-invalid/all-empty outcomes.
- Deterministic scalar precedence, unchanged-value no-op timestamps, and per-ISIN request ordering.
- Append-only collection semantics, partial entries, same-request duplicates, and repeated submissions.
- Atomic publication rollback, successful no-change requests, and redelivery after commit.

## 14. Trade-first security enrichment

### 14.1 Agreed behavior

Trades arrive before security details by design. Security details are fetched only for securities that have traded, rather than preloading the full universe.

- Security details explicitly contain `isin` as their primary identifier.
- When publishing an accepted CSV trade, create a minimal security record containing only its ISIN if no security exists. Processing metadata is separate from these business attributes.
- Trigger a security-details event only when an accepted trade inserts a previously nonexistent ISIN into `securities_data.securities`. An existing security does not trigger a new event, whether fully populated, partially populated, or ISIN-only.
- Quarantined CSV rows do not trigger security creation or enrichment requests.
- JSON processing later enriches the same ISIN using the agreed scalar and collection rules. It does not create another identity or replace accepted trades.
- Security-details retrieval is asynchronous; successful trade publication does not wait for it to finish.

### 14.2 CloudEvents contract and reliable publication

Use the released [CloudEvents v1.0.2 specification](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md) and [JSON event format](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/formats/json-format.md). Its wire attribute is `specversion: "1.0"`, not `"1.0.2"`. The required envelope attributes are `specversion`, `id`, `source`, and `type`; our application contract additionally requires the fields shown below.

One event represents the occurrence of a committed request to retrieve security details. It does not represent successful retrieval. The existing download service is the consumer. The security-details queue is separate from the file-processing queue. The event type and payload follow the existing fetch-service contract shown below.

#### External queue ownership and configuration

The security-details queue is supplied by its external owner, not provisioned by this service's Terraform. AWS identifies it with an ARN; the application also needs its queue URL because [SQS SendMessage](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_SendMessage.html) addresses the destination using `QueueUrl`.

Proposed deployment inputs are `security_details_queue_arn` and `security_details_queue_url`, injected into application configuration as appropriate. The queue's region and Standard/FIFO type must be provided or resolved during configuration. If FIFO is supplied, the publisher must supply the required message-group and deduplication parameters; the external queue type is not implicitly fixed by this document's separate internal-processing FIFO proposal.

This project's Terraform may configure its own Lambda function-role permission to send messages to the supplied ARN. Queue policies, queue creation/deletion, retention, redrive/DLQ configuration, and encryption-key ownership remain with the external owner. Any customer-managed-key permissions required for sending must be coordinated with that owner. Do not put the external queue or its DLQ under this project's Terraform resource lifecycle, including via import. Terraform destruction for this service must leave them intact.

SQS delivery failures continue to use the durable outbox retry behavior. The CloudEvent `source` URI remains the producer identity, not the supplied queue ARN or URL. This ownership decision applies to the outbound ISIN details-request queue; it does not change the separate file-processing queue design.

```json
{
  "specversion": "1.0",
  "id": "bfed74ba-84ee-45b6-8a7a-50fbb53a0cbb",
  "source": "urn:bond-platform:structured-file-processing",
  "type": "com.bondplatform.data.pull.requested.v1",
  "subject": "isin/INE831R08076",
  "time": "2026-09-26T10:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "schema_version": 1,
    "event_type": "nsdl-bond-data",
    "inputs": {
      "isin_code": "INE831R08076"
    }
  }
}
```

Application field contract:

| Field | Application meaning/rule |
|---|---|
| `id` | UUID generated once when the durable enrichment request is created; persist it in the outbox |
| `source` | Stable configured producer identity including environment; not a container ID, queue URL, or download URL |
| `type` | Existing fetch-service routing type `com.bondplatform.data.pull.requested.v1` |
| `subject` | `isin/` followed by exactly the normalized `data.inputs.isin_code` |
| `time` | UTC time recorded when the durable request is created; not reset on delivery retries |
| `datacontenttype` | `application/json`, describing the nested payload |
| `data.schema_version` | Required integer `1` |
| `data.event_type` | Required value `nsdl-bond-data` |
| `data.inputs.isin_code` | Required nonblank, trimmed uppercase ISIN; retain the agreed absence of format/check-digit validation |

CloudEvents identifies duplicates by `(source, id)`; its timestamp uses RFC 3339. The fetch service owns interpretation of `data.event_type` and `data.inputs`; the processor must produce the exact snake_case names shown above. For manifest ingestion, `type` is `com.bondplatform.dataset.manifest.v1` and `dataschema` is the dataset contract URN used to select the processor-owned YAML contract. The identifiers and validation rules in the table are our service contract, not additional requirements imposed by CloudEvents.

SQS transport convention: serialize one complete CloudEvent as UTF-8 JSON in `MessageBody`. Use an application-defined string message attribute `contentType=application/cloudevents+json` to identify the envelope. This is our SQS convention, not a claimed standard SQS protocol binding. Keep `data` as a JSON object rather than a string containing serialized JSON. The queue destination remains deployment configuration, outside the event. No EventBridge or SNS wrapper is needed for this direct SQS path.

Proposed implementation: in the same PostgreSQL transaction as the accepted trades, insert missing securities and persist an outbox event only for each ISIN actually inserted by this transaction. Send only committed events to SQS through a retrying dispatcher. Do not send an event directly before commit: the trade could subsequently roll back. Do not rely on a one-off send after commit without a durable outbox: a crash could otherwise lose the request.

A temporary SQS failure leaves the event pending for delivery; it does not undo committed trades or require republishing the CSV. Track delivery failure/retry independently from the completed trade-processing job.

### 14.3 Event deduplication without a separate enrichment table

The user has removed the proposed `security_enrichment_requests` table. Do not create a separate enrichment lifecycle or per-field completeness tracker. The outbox tracks event delivery; existing JSON ingestion/job records track processing results. The download service owns its fetch execution state.

The dispatcher preserves the same CloudEvent envelope across send retries. The consumer durably deduplicates by `(source, id)`; transport retries must not start duplicate download jobs. A genuinely new intentional fetch request receives a new event ID. SQS transport identity is not business identity.

Use the database insert result as the trigger, rather than an unchecked read followed by an insert. Proposed PostgreSQL implementation: `INSERT ... ON CONFLICT (isin) DO NOTHING RETURNING isin`; create an outbox event only for a returned ISIN. Execute the security insert, accepted trade publication, and event intent in one transaction. The ISIN primary key ensures concurrent transactions cannot both create the same security and trigger new initial events.

Repeated trades and retries find the existing security and create no new details-request event. A failed transaction rolls back both security creation and its event intent; a retry can then create both together. Outbox delivery retries resend the original event, independently of the new-security trigger. An existing ISIN-only record with no prior event is not automatically backfilled by this flow.

### 14.4 Completion boundary and unresolved details

The agreed trigger is creation of a previously nonexistent security during accepted-trade processing. Do not check individual data points or infer completeness to decide whether to publish. Later trades never initiate a fresh fetch for an existing ISIN, including after a failed or empty download. Any future manual retry/refresh feature is separate from this trigger and is not part of the currently agreed automatic flow.

The existing download service consumes this queue, retrieves the security JSON, writes completed immutable S3 files and their manifest, and calls `POST /v1/event-ingestions`. Its own LLD governs durable admission and SQS acknowledgment. The handoff carries a complete manifest rather than a prefix to scan.

The fetch-service contract uses its delivery run ID as `Idempotency-Key`, preserving it across delivery retries. Its request includes `event_id`, `run_id`, manifest location, and dataset fingerprint. Retain those identities to correlate the original trigger with the processing job. This replaces the earlier proposed derived event-ID hash for the return-call key. Changed payload under the same key remains an idempotency conflict.

The downloader stops after the processor durably accepts with HTTP 202; it does not poll for business completion. Successful delivery does not establish that security details were applied. The JSON job still exposes completion/partial-error/failure status through read APIs. Subsequent trades do not automatically create replacement fetch requests. Tables and indexes are in section 22.

### 14.5 Verification

- First accepted trade creates the placeholder security, trade, and durable event intent together.
- A transaction rollback publishes neither trade changes nor a security-details event.
- Multiple or concurrent accepted trades for a new ISIN result in one security insert and one initial outbox event.
- Existing securities, including ISIN-only securities without an earlier event, do not generate new events from subsequent trades.
- Quarantined trades create no enrichment work.
- SQS failure/crash after trade commit leaves a recoverable outbox event.
- Duplicate event delivery does not create duplicate downstream fetch jobs.
- JSON enrichment associates with the original ISIN and preserves existing daily trades.
- Validate required event attributes, the known event type/version, normalized payload ISIN, and matching subject before creating a download job.
- Preserve event identity and occurrence time through outbox retries, queue redelivery, and recovery.
- Repeated downstream manifest submissions reuse the fetch-service run-ID key and return the same processing job with HTTP 202.
- Unknown additive fields can be ignored; unsupported event major versions and malformed envelopes must not trigger a fetch and need a recorded delivery failure under the eventual dead-letter policy.

## 15. Internal model review — agreed field names

Agreed project convention:

| Layer | Convention | Examples |
|---|---|---|
| Java classes/records | UpperCamelCase | `Security`, `DailyTrade` |
| Java fields and methods | lowerCamelCase | `issuerName`, `exchangeName`, `updatedAt` |
| Application JSON properties | lowerCamelCase | `issuerName`, `couponRate`, `sourceJobId` |
| PostgreSQL schemas, tables, columns | lowercase snake_case | `issuer_name`, `exchange_name`, `updated_at` |
| Constants/enum values | UPPER_SNAKE_CASE | `PERCENT`, `COMPLETED_WITH_ERRORS` |

This is a project convention, not a universal JSON/SQL naming standard. Java field naming follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html#s5.2.5-non-constant-field-names). Lowercase SQL names avoid case-sensitive quoted identifiers under [PostgreSQL identifier rules](https://www.postgresql.org/docs/16/sql-syntax-lexical.html#SQL-SYNTAX-IDENTIFIERS). Map Java names to SQL column names explicitly in the persistence layer. Tables use plural names; agreed schema and business-table names are in section 17.

Preserve external source property names exactly in source contracts and raw evidence. CloudEvents standard envelope attributes retain their prescribed spelling, such as `specversion` and `datacontenttype`; do not convert them to camelCase. The event `data` payload follows application JSON naming.

The proposed security record uses ISIN identity and the selected issuer, instrument, coupon, and listing-status fields plus creation/update timestamps. The user has removed `newIsin`; it must not appear in the accepted internal model or new database schema.

### 15.1 Asset-cover naming and expanded scope

Agreed mapping: `$.instrumentsVo.assetCover.securedFlag` maps to `collateralStatus` in Java/API JSON and `collateral_status` in PostgreSQL. This is a text value such as `Unsecured`, not a boolean. It replaces the proposed internal names `securedFlag` and `securityStatus`; the external source path remains unchanged.

The entire supplied `assetCover` structure is selected. A second, populated sample establishes the structure below; these internal names and types have been agreed.

| Source relative to `$.instrumentsVo.assetCover` | Internal Java/API field | Type |
|---|---|---|
| `securedFlag` | `collateralStatus` | Text |
| `assetCvge` | `assetCoverageBasis` | Text, for example `Principal + Interest` |
| `assetCvrPercent` | `assetCoverage.value` | Exact decimal, for example `100` |
| Declared unit | `assetCoverage.unit` | `PERCENT` |
| `assetList[*]` | `collateralAssets[]` | 1:N collection associated with ISIN |
| `assetList[*].assetType` | `collateralAssets[].assetType` | Text |
| `assetList[*].securityDetails` | `collateralAssets[].collateralDescription` | Text |
| `assetList[*].remarks` | `collateralAssets[].remarks` | Optional text |

```json
{
  "isin": "INE831R08076",
  "collateralStatus": "Secured",
  "assetCoverageBasis": "Principal + Interest",
  "assetCoverage": {
    "value": 100,
    "unit": "PERCENT"
  },
  "collateralAssets": [
    {
      "assetType": "Book Debts / Receivables",
      "collateralDescription": "Secured by Vehicle Finance Loan Receivables/LAP Receivables.",
      "remarks": null
    }
  ]
}
```

The ISIN in this illustrative object is filename-derived, not a newly required field within the source asset-cover section. Preserve native JSON values and exact JSONPaths in canonical evidence. Original source names do not change. PostgreSQL names will follow the agreed snake_case convention, with final tables/columns reviewed separately.

### 15.2 Asset-cover validation and history

- Coverage accepts JSON numbers and numeric strings with an optional `%`: `100`, `"100"`, and `"100%"` represent 100 percent.
- Preserve decimal precision; zero and values above 100 are allowed. Negative values quarantine only the coverage field.
- Collateral assets follow the agreed append-only collection policy: compare all selected normalized fields within ISIN/collection, skip identical entries, and append distinct entries without updating or deleting historical records.
- Retain source lineage and the first-recorded timestamp for a distinct asset entry. Do not add a per-submission collection-membership model now. Consequently, this collection is distinct observed history, not a complete chronology of removals or returns to a previously seen value.
- `Unsecured` makes coverage basis, coverage value/unit, and collateral assets inapplicable in the current security view. Clear current coverage values and expose no applicable assets, preserving historical asset records separately. This is a deliberate exception to the ordinary JSON null/no-update rule, triggered by an explicit valid unsecured status.
- If an asset-cover section explicitly states `Unsecured` but supplies meaningful coverage values or nonempty assets, log and persist a data-consistency error. Ignore the conflicting coverage and do not append those supplied assets; apply the valid unsecured status. Retain conflicting raw values and paths for review. Nulls, placeholders, and an empty asset list do not themselves create this conflict.
- The proposed code is `CONFLICTING_COLLATERAL_DATA`. The agreed job outcome is `COMPLETED_WITH_ERRORS` when applying a valid unsecured status while ignoring conflicting collateral data.

Agreed v1 presentation: while `collateral_status` is `Secured`, the read API returns every collateral asset recorded for the ISIN with its `first_recorded_at`; while it is `Unsecured`, it returns none. If a security becomes secured again, all previously recorded assets are presented again together with any new ones. The API does not claim which assets belong to the latest source file; do not infer current collection membership from this append-only history. Per-submission membership tracking stays deferred.

### 15.3 Security field names

| Internal field | Database column | Source/meaning |
|---|---|---|
| `isin` | `isin` | Primary security identity |
| `issuerName` | `issuer_name` | `issuerName` |
| `issuerOwnershipType` | `issuer_ownership_type` | `issuerTypeOwner`, such as `Non PSU` |
| `instrumentType` | `instrument_type` | `instrumentType` |
| `allotmentDate` | `allotment_date` | Main instrument `allotmentDate` |
| `redemptionDate` | `redemption_date` | Scheduled `redemptionDate`; retain this name rather than maturity date |
| `originalFaceValue` | `original_face_value` | Instrument `issuePrice`; exact non-negative decimal, JSON number or grouped numeric string |
| `collateralStatus` | `collateral_status` | `securedFlag` |
| `assetCoverageBasis` | `asset_coverage_basis` | `assetCvge` |
| `assetCoverage.value` | `asset_coverage_value` | Exact percentage value |
| `assetCoverage.unit` | `asset_coverage_unit` | `PERCENT` |
| `couponRate.value` | `coupon_rate_value` | Exact coupon percentage |
| `couponRate.unit` | `coupon_rate_unit` | `PERCENT` |
| `couponType` | `coupon_type` | `couponType` |
| `listingStatus` | `listing_status` | Root `listingStatus` |
| `createdAt` | `created_at` | Record creation timestamp |
| `updatedAt` | `updated_at` | Last actual business-value change |

Only ISIN is mandatory at creation. Field-level canonical lineage is separate from these business attributes; a single source reference cannot describe all scalar fields because they can originate from different files/jobs.

### 15.4 Payment schedule field names

Agreed mapping: filename ISIN to `isin`; `cashFlowsEvent` to `eventType`; retain `recordDate`, `dueDate`, `amountPayable`, and `paymentDate`; `newFaceVal` to `newFaceValue` (added 2026-10-02). Corresponding columns are `isin`, `event_type`, `record_date`, `due_date`, `amount_payable`, `payment_date`, and `new_face_value`. `newFaceValue` is the face value after the event; NSDL supplies it on `Partial Redemption` events and sends a placeholder otherwise, so it is empty for other events. It is an exact non-negative decimal accepted as a JSON number or grouped numeric string, and it is part of the entry's identity like every other selected field.

Each distinct schedule entry also has its own identifier, first-recorded timestamp, and source lineage. It follows the agreed append-only identity based on selected normalized business values, not those generated metadata fields.

### 15.5 Listing field names

Agreed fields are `isin`, `exchangeName`, and `listingDate`, mapped to `isin`, `exchange_name`, and `listing_date`. `listingStatus` belongs to the security record, not each listing entry. Each distinct listing has its own identifier, first-recorded timestamp, and source lineage.

### 15.6 Rating field names

| Source | Internal field | Database column |
|---|---|---|
| Filename ISIN | `isin` | `isin` |
| `creditRatingAgencyName` | `ratingAgencyName` | `rating_agency_name` |
| `currentRating` | `rating` | `rating` |
| `outlook` | `outlook` | `outlook` |
| `ratingAction` | `ratingAction` | `rating_action` |
| `creditRatingDate` | `ratingDate` | `rating_date` |
| `ratingChangeDate` | `ratingChangeDate` | `rating_change_date` |
| `dateOfVerification` | `verificationDate` | `verification_date` |
| Source array membership | `sourceCategory` | `source_category` |

`sourceCategory` is `CURRENT` or `EARLIER`, preserving the source array. It is an observation label, not proof that a previously stored rating remains currently effective. Each distinct rating retains an identifier, first-recorded timestamp, and source lineage. Include source category in collection identity as already agreed.

### 15.7 Daily trade field names

Agreed internal fields are `isin`, `tradeDate`, `exchangeName`, `securityCode`, `openPrice`, `highPrice`, `lowPrice`, `closePrice`, `tradedVolume`, `numberOfTrades`, `turnover`, and `faceValue`. Database columns are their snake_case equivalents. Retain creation/update timestamps and canonical lineage. The unique key is `(isin, tradeDate, exchangeName)`.

Business-table names are recorded in section 17. Types, constraints, and processing tables are in section 22.

## 16. Combined job-error review

Agreed approach for both CSV and JSON: `GET /v1/processing-jobs/{jobId}` returns status, processing counts, total error count, and a preview of at most the first five errors. Return all errors when fewer than five exist and an empty array when there are none. Each preview entry includes its source filename, CSV record number or JSONPath when applicable, error code, and message. A separate paginated endpoint returns the complete list. Store reviewable errors in PostgreSQL; application logs are supplementary and are not the sole user-facing record. Downloadable reports are deferred. Non-blocking warnings, including a payload ISIN that differs from the filename-derived ISIN, remain application-log entries only and are excluded from `errorCount`, error previews, and the paginated error endpoint.

Public status and error responses expose the source filename and logical location (CSV record number or JSONPath) for review, but never expose S3 bucket names, S3 keys, S3 version IDs, presigned URLs, or upstream `source_url` values. S3 lineage remains available to internal operators through protected infrastructure access and persisted processing records.

Response shape (final contract in section 20.3 and the read OpenAPI document):

```json
{
  "jobId": "job-123",
  "status": "COMPLETED_WITH_ERRORS",
  "errorCount": 1,
  "errors": [
    {
      "errorId": "error-123",
      "code": "CONFLICTING_COLLATERAL_DATA",
      "sourceFile": "INE831R08076_instruments.json",
      "path": "$.instrumentsVo.assetCover.assetCvrPercent",
      "rawValue": 100,
      "message": "Asset coverage was supplied for an Unsecured instrument.",
      "actionTaken": "Ignored supplied coverage and cleared current coverage values."
    }
  ],
  "hasMoreErrors": false,
  "errorsUrl": "/v1/processing-jobs/job-123/errors"
}
```

Each error identifies the source file/object, the JSONPath or CSV record/column, rejected source value where applicable, stable error code, explanation, and action taken. File/request errors need not have a field path. Persistent error IDs make inline preview entries identifiable in the full list. Attempt identity and canonical references support traceability without combining retry histories into misleading final-job counts.

Agreed: include the original invalid field value as `rawValue` in both the status preview and paginated error responses. For example, an invalid allotment date exposes `rawValue: "32-13-2026"` alongside its path and validation message. Use the source value before normalization, not the attempted converted value. Omit `rawValue` when no source value exists (such as a missing field); do not invent an empty string. Agreed presentation limit: show at most the first 1,000 Unicode characters of an invalid text value in `rawValue`, with `rawValueTruncated: true` when shortened and `false` otherwise. This applies to both inline previews and paginated errors. Truncation is for API presentation only; retain the full value in stored evidence. Never split a Unicode character. Large object/array values use a serialized JSON text preview subject to the same limit; their full structure remains in evidence.

The preview count limit is five. `errorCount` is the full error count, not the preview length; `hasMoreErrors` is true when more errors exist than are shown. `GET /v1/processing-jobs/{jobId}/errors` returns up to 50 errors per page and an opaque cursor `nextToken` when additional errors exist. The client supplies that token as the next request's `pageToken`; it must be treated as opaque and may expire. The endpoint supports one optional `isin` query filter; trim and uppercase it before matching the persisted normalized ISIN. No other error filters are supported in v1. For example, 120 errors require pages of 50, 50, and 20 entries. The preview must also have bounded size; warnings are log-only and attempt selection is agreed below. Inline and paginated results must come from the same persisted errors with consistent ordering. A status summary describes applied results, not merely intended actions before transaction commit.

Agreed attempt selection: `errorCount`, the inline preview, and the paginated error endpoint present only the errors of the final applicable attempt. Temporary failures of earlier attempts (for example S3 unavailability on attempt 1 before a successful attempt 2) are retained in internal attempt records and application logs for troubleshooting and are not returned by the public error-review API. This is consistent with counts describing the final attempt rather than a sum across retries.

## 17. Schema and table naming

### 17.1 Agreed namespaces and business tables

Use `securities_data` for accepted security reference data, scheduled cash flows, listings, credit-rating observations, collateral history, and daily market aggregates. This name covers both reference and market data; `market_data` alone was less descriptive of the full scope.

Use `data_processing` for ingestion jobs and attempts, source-file references, canonical evidence, validation/quarantine issues and transactional outbox bookkeeping. These are operational processing records, not accepted securities-domain records.

| Qualified table name | Record meaning |
|---|---|
| `securities_data.securities` | One security identified by ISIN, including an initial ISIN-only record |
| `securities_data.security_cash_flows` | Distinct scheduled interest/redemption cash-flow observations |
| `securities_data.security_listings` | Distinct exchange listing observations |
| `securities_data.security_ratings` | Distinct credit-rating observations with current/earlier source category |
| `securities_data.security_collateral_assets` | Distinct append-only collateral asset observations |
| `securities_data.security_daily_market_summaries` | One aggregate for `(isin, trade_date, exchange_name)`, containing OHLC, volume, trade count, and turnover |

Earlier references to a daily trade/current trade describe the aggregate in `security_daily_market_summaries`; no individual trade-execution table is intended. A future table for actual executions would be a different entity. Keep the existing approved column names, including `trade_date`.

### 17.2 Naming rationale and implementation convention

[PostgreSQL schemas](https://www.postgresql.org/docs/15/ddl-schemas.html) support logical grouping and namespacing. These two namespaces separate business purpose while retaining one database and transactions across schemas.

Use lowercase snake_case and unquoted identifiers, meaningful complete words, and plural entity table names. Avoid technical prefixes such as `tbl_`, environment names in table names, and file-format suffixes such as `_csv` or `_json` for shared business tables. The source format belongs in processing metadata. Retain `security_` in related business tables as requested, so names remain meaningful in logs and tools that omit the schema.

Singular/plural choice is a project convention rather than a financial standard; [published SQL naming guidance](https://www.sqlstyle.guide/) favors consistency and descriptive collective/plural names. PostgreSQL's usual identifier limit is 63 bytes, so check generated constraint/index names as well as table names. Use schema-qualified names in migrations and explicit persistence mappings.

Do not label accepted observation tables `current_*`: append-only records do not automatically represent the latest state. Processing/canonical history remains separate from the accepted observations. Processing tables are in section 17.5; definitions in section 22; retention in section 21.1.

### 17.3 Agreed primary keys and security foreign keys

| Business table | Primary key |
|---|---|
| `securities` | `isin` |
| `security_cash_flows` | UUID `id` |
| `security_listings` | UUID `id` |
| `security_ratings` | UUID `id` |
| `security_collateral_assets` | UUID `id` |
| `security_daily_market_summaries` | Composite `(isin, trade_date, exchange_name)`; no additional UUID |

Each child table has an ISIN foreign key to `securities_data.securities(isin)` with `ON DELETE RESTRICT`. A security cannot be deleted while related business records exist. This applies to both append-only collections and daily market summaries. Column types for ISIN must match across these tables.

UUID identity does not replace business duplicate prevention. Append-only collection entries still need the agreed deduplication by ISIN, collection identity, and selected normalized values; enforcement is a `NULLS NOT DISTINCT` unique index (section 22.1).

### 17.4 Agreed timestamps and dates

- Use PostgreSQL `TIMESTAMPTZ` for recorded instants and return API timestamps in UTC.
- `securities` and `security_daily_market_summaries` have `created_at` and `updated_at`.
- Append-only cash flows, listings, ratings, and collateral assets have `first_recorded_at`.
- Business calendar dates, including trade, allotment, redemption, listing, and payment dates, use `DATE`.
- Preserve the security no-op rule: identical incoming scalar business values do not update its row or `updated_at`.
- Preserve first-recorded timestamps when duplicate collection entries are skipped. These timestamps record first persistence, not a claim that the source business event happened then.
- Canonical/job/attempt timestamps and complete source lineage remain separate from the business dates.

### 17.5 Agreed canonical storage and references

Revised 2026-10-02, superseding the earlier decision to hold all canonical data in PostgreSQL (and any earlier statement in this document that canonical history is stored in PostgreSQL). Original source files remain in S3.

- Rejected canonical records only (CSV rows with disposition `QUARANTINED_INVALID` or `QUARANTINED_SUPERSEDED`; JSON fields, entries, sections, or files that fail validation) are stored in PostgreSQL in `data_processing`, with their raw selected values, location, and errors. They back the error-review API.
- The complete canonical output of each processing run — accepted and rejected records alike — is written to S3 as an immutable file per run. Accepted canonical records are not stored in PostgreSQL.
- Business rows reference their origin by ingestion request and location (source filename plus CSV record number or JSONPath) rather than by a database foreign key to a canonical record. The security's `field_sources` JSONB (API-internal, not exposed) uses the same reference form per field. Identical incoming values leave the value and its reference unchanged; skipped duplicate collection entries keep their original reference.
- Duplicate-ISIN winner selection and JSON scalar precedence are computed during the run from the in-flight canonical records, not from persisted database rows.

Agreed canonical file location: the processing service owns a dedicated S3 bucket, created by its persistent Terraform root so it survives application teardown like the database. It is separate from the fetch-service artifact bucket, whose objects expire after 30 days. Each run writes one immutable JSON Lines object, `canonical/{jobId}/attempt-{n}/canonical.jsonl`, one canonical record per line. A business row's origin reference `(jobId, sourceFile, recordNumber | jsonPath)` is sufficient to locate its line. The object key is recorded on the processing run and is never exposed by public APIs.

Agreed `data_processing` tables:

| Table | One row is |
|---|---|
| `ingestion_requests` | One accepted submission (the API "processing job"): idempotency key, dataset URN, pinned contract versions, status, counts |
| `processing_runs` | One processing attempt, including the S3 key of its canonical file |
| `source_files` | One file listed in the request's manifest, with checksum and size |
| `rejected_records` | One rejected CSV row or JSON value/entry/section/file, with raw selected values and location |
| `validation_issues` | One reviewable error returned by the error-review API |
| `outbox_events` | One event durably recorded for delivery to SQS |

`source_files` and `processing_runs` belong to an ingestion request; `rejected_records` and `validation_issues` belong to a processing run. There is no `canonical_records` table.

There is no `security_enrichment_requests` table. Initial details-fetch event intent and delivery are represented through the outbox; resulting JSON processing uses the existing ingestion records.

## 18. Persistent infrastructure lifecycle

Agreed: destroying application infrastructure does not delete the PostgreSQL database or its data. Use separate Terraform roots/states for persistent infrastructure and disposable application infrastructure. The standard application-destroy GitHub Actions workflow targets only the disposable root.

The persistent lifecycle includes RDS and dependencies whose removal would break or compromise preservation, such as database networking, credentials, and any database encryption key. Exact resource placement will be established during infrastructure design. Application deployments consume persistent outputs rather than assume ownership of these resources.

Proposed safeguards are RDS deletion protection and Terraform `prevent_destroy` on critical persistent resources. These supplement lifecycle separation; snapshots are not a substitute for retaining the agreed live database. Any eventual database teardown requires a separate explicit operation and policy, not the standard application-destroy action. Backups are agreed in section 21.1.

The externally owned security-details queue and upstream S3 source storage also remain outside the application Terraform destroy lifecycle. The internal processing queue is owned by the application Terraform (section 21). Accepted jobs are durable in PostgreSQL; after a teardown and redeploy, the sweeper re-dispatches undelivered or unfinished jobs from the outbox and job state.

## 19. Fetch-service contract alignment

Reviewed local references on 2026-09-27:

- [Fetch-service LLD](https://github.com/maverickuser/data-fetch-service/blob/main/docs/specs/data-puller-service-lld.md), section 11, defines the manifest and `POST /v1/event-ingestions` contract.
- [Fetch-service implementation plan](https://github.com/maverickuser/data-fetch-service/blob/main/docs/plans/data-fetch-service-implementation-plan.md), PR 06, implements complete-event manifests; PR 07 implements processor delivery and durable HTTP-202 acceptance.

Agreed integration: manifest-based admission replaces direct object/prefix submission. The fetch-service documents already specify that mechanism. A subsequent shared-contract clarification requires BSE manifest `inputs.exchangeName` and `inputs.tradeDate`; this is recorded in both local LLDs and the fetch-service implementation plan. The design review completed on 2026-10-02.

The upstream manifest is a CloudEvent carrying `specversion`, `id`, `source`, `type`, `dataschema`, `time`, `subject`, `datacontenttype`, and `data`. The `type` is `com.bondplatform.dataset.manifest.v1`; `dataschema` is the single dataset contract URN used to select the processor-owned YAML contract. The `data` object carries `schema_version`, `event_type`, `event_id`, `run_id`, `inputs`, `config_revision`, `dataset_fingerprint`, and `files[]`. Each file entry carries `job_id`, `bucket`, `key`, `format`, `sha256`, `size_bytes`, and `source_url`; it never carries a processing contract identifier. The manifest contains the full upstream job set, not just changed files. BSE includes only the extracted CSV, not its ZIP or unselected CSV members. NSDL currently emits six JSON files, including a redemptions response beyond the five user-selected source samples.

Do not use `source_url` to download anything in the processor; it is provenance metadata. Read the explicitly listed S3 objects under the agreed storage allowlist. Manifest fields do not authorize arbitrary code execution or remote rule loading. The v1 contract selector is `dataschema`; `job_id` preserves provenance and never limits extraction of configured JSON paths. Agreed (2026-10-02): the sixth NSDL file (`{isin}_redemptions.json`) is not mapped in v1. The v1 contract selects no paths from it; it is still listed in the manifest, checksum-verified, parsed, counted in `filesListed`/`filesProcessed`, and retained in S3, but contributes no business data and raises no error for having no selected fields.

Agreed checksum handling: verify each listed object's `sha256` (and `size_bytes`) against the manifest before parsing it. A mismatch is a content error with code `CHECKSUM_MISMATCH` and is never automatically retried, because listed objects are immutable. For a CSV manifest the single file's mismatch makes the job `FAILED` with no business-data changes. For a JSON manifest only the mismatching file is skipped, exactly as for malformed JSON; the remaining listed files are processed and the job ends `COMPLETED_WITH_ERRORS` (or `FAILED` when no usable selected data remains). Correction requires a new fetch-service run and submission.

Manifest problems (agreed 2026-10-03): a manifest that is not a well-formed manifest event, lists no file, lists the same object twice, lists files of another format than the dataset's source contract reads, or lists more than one CSV fails the job with `INVALID_MANIFEST`. A manifest whose `dataschema`, `subject`, `data.event_type`, `data.event_id`, `data.run_id`, `data.inputs`, or `data.dataset_fingerprint` differs from the accepted submission also fails with `INVALID_MANIFEST`, its detail naming the fields that differ; the two events' own `id` and `time` are not compared. Listed sizes over the section 1 limits fail with `SOURCE_TOO_LARGE`. None of these is retried. Only the buckets configured as source buckets are read; a manifest naming another bucket is `INVALID_MANIFEST`. A storage error that may pass (throttling, a server error, a timeout) fails the attempt with `SOURCE_UNAVAILABLE`, which is retried; S3 answers 403 for a missing key when listing is not allowed, so 403 is treated as `SOURCE_NOT_FOUND`; the price is that an IAM misconfiguration fails jobs instead of retrying them. S3 client errors that pass on their own (an expiring credential, clock skew, a request timeout) are `SOURCE_UNAVAILABLE`. A file's `sha256` is accepted as 64 hex digits with or without a `sha256:` prefix: the fetch service's code writes plain hex while its LLD examples show the prefix.

Compatibility questions closed 2026-10-02: shared-network layout is in section 21.2; upstream 30-day expiry is handled by the processor's own canonical bucket (section 17.5) and `SOURCE_NOT_FOUND` (section 21).

The fetch service stops at durable HTTP 202 and does not need to poll. Processor status/error APIs remain for independent review. These two service completion boundaries must remain distinct.

## 20. Read APIs

Agreed v1 read surface (2026-10-02). All endpoints are public, unauthenticated, read-only `GET` operations on `processing.kagent.app`; none accepts S3 locations or changes data.

| Endpoint | Returns |
|---|---|
| `GET /v1/processing-jobs/{jobId}` | Job status, counts, total error count, and the first five errors (section 16) |
| `GET /v1/processing-jobs/{jobId}/errors` | All final-attempt errors, 50 per page with cursor pagination and an optional `isin` filter (section 16) |
| `GET /v1/securities/{isin}` | One combined security view: scalar details, current ratings and rating history, listings, cash-flow schedule, and collateral assets |
| `GET /v1/securities/{isin}/daily-market-summaries` | Daily market summaries for one ISIN, with optional `fromDate` and `toDate` |
| `GET /v1/daily-market-summaries?tradeDate=` | All summaries for one trade date, optional `exchangeName`, 50 per page with cursor pagination |

Separate per-collection endpoints for ratings, listings, and cash flows are not part of v1. Path and query ISINs are trimmed and uppercased before lookup.

### 20.1 Security response

Agreed shape of `GET /v1/securities/{isin}`:

```json
{
  "isin": "INE831R08076",
  "issuerName": "ADITYA BIRLA HOUSING FINANCE LIMITED",
  "issuerOwnershipType": "Non PSU",
  "instrumentType": "Debentures",
  "allotmentDate": "2019-06-10",
  "redemptionDate": "2029-06-08",
  "originalFaceValue": 1000000,
  "couponRate": { "value": 8.94, "unit": "PERCENT" },
  "couponType": "Simple",
  "listingStatus": "Listed",
  "collateralStatus": "Unsecured",
  "assetCoverageBasis": null,
  "assetCoverage": null,
  "collateralAssets": [],
  "currentRatings": [
    { "ratingAgencyName": "INDIA RATING AND RESEARCH PVT. LTD", "rating": "AAA", "outlook": "Stable",
      "ratingAction": "Reaffirm", "ratingDate": "2019-02-15", "ratingChangeDate": null,
      "verificationDate": null, "firstRecordedAt": "2026-09-27T14:31:02Z" }
  ],
  "ratingHistory": [],
  "listings": [
    { "exchangeName": "NSE", "listingDate": "2019-06-14", "firstRecordedAt": "2026-09-27T14:31:02Z" }
  ],
  "cashFlows": [
    { "eventType": "Interest", "recordDate": "2027-05-24", "dueDate": "2027-06-08",
      "amountPayable": 89400.00, "paymentDate": null, "newFaceValue": null, "firstRecordedAt": "2026-09-27T14:31:02Z" }
  ],
  "createdAt": "2026-09-21T15:00:00Z",
  "updatedAt": "2026-09-27T14:31:02Z"
}
```

- Every property is always present; a scalar without a value is `null` and an empty collection is `[]`. `couponRate` and `assetCoverage` are `null` as a whole when no value exists.
- `currentRatings` holds one entry per agency chosen by the section 13.6 rule; `ratingHistory` holds every other rating observation (including `EARLIER`-category rows), newest `ratingDate` first. `collateralAssets` follows section 15.2.
- An ISIN-only security created by a trade returns the same shape with nulls and empty collections.
- An unknown ISIN returns `404` as RFC 9457 `application/problem+json`.
- The internal `fieldSources` map, canonical record identifiers, and S3 lineage are not exposed.
- Decimals are JSON numbers serialized exactly; dates are ISO `YYYY-MM-DD`; timestamps are UTC RFC 3339.

### 20.2 Daily market summary responses

Agreed. Both daily-market-summary endpoints return `{ "items": [...], "nextToken": null }` where each item is:

```json
{
  "isin": "INE0KH208019",
  "tradeDate": "2026-01-01",
  "exchangeName": "BSE",
  "securityCode": "976009",
  "openPrice": 114200.00,
  "highPrice": 114200.00,
  "lowPrice": 114200.00,
  "closePrice": 114200.00,
  "tradedVolume": 14,
  "numberOfTrades": 1,
  "turnover": 1598800.00,
  "faceValue": 100000.00,
  "createdAt": "2026-01-01T15:00:00Z",
  "updatedAt": "2026-01-01T15:00:00Z"
}
```

- `GET /v1/securities/{isin}/daily-market-summaries`: ordered by `tradeDate` descending then `exchangeName`; 50 per page; optional inclusive `fromDate` and `toDate` (ISO dates). Without either, the most recent 50 are returned and paging continues backwards. Unknown ISIN returns `404`; a known ISIN with no matching summaries returns `200` with empty `items`.
- `GET /v1/daily-market-summaries`: `tradeDate` is required; optional `exchangeName`; ordered by `isin` then `exchangeName`; 50 per page. No matches returns `200` with empty `items`.
- Pagination uses the same opaque cursor convention as the error list: `pageToken` request parameter, `nextToken` in the response, `null` on the last page.
- A malformed date, `fromDate` after `toDate`, a missing `tradeDate`, or an invalid `pageToken` returns `400` as `application/problem+json`.

### 20.3 Job status response

Agreed shape of `GET /v1/processing-jobs/{jobId}`. The outer shape is common; `counts` depends on the dataset.

```json
{
  "jobId": "job-123",
  "dataset": "urn:bond-platform:dataset:bse-debt-trades",
  "status": "COMPLETED_WITH_ERRORS",
  "subject": "exchange/BSE/trade-date/2026-01-01",
  "attemptCount": 2,
  "submittedAt": "2026-01-01T14:30:00Z",
  "startedAt": "2026-01-01T14:30:05Z",
  "completedAt": "2026-01-01T14:30:20Z",
  "counts": {
    "sourceRecords": 100,
    "acceptedRows": 95,
    "invalidRows": 3,
    "supersededRows": 2
  },
  "errorCount": 5,
  "errors": [],
  "hasMoreErrors": false,
  "errorsUrl": "/v1/processing-jobs/job-123/errors"
}
```

For `urn:bond-platform:dataset:nsdl-security` jobs `counts` is:

```json
{
  "filesListed": 6,
  "filesProcessed": 5,
  "filesSkipped": 1,
  "scalarFieldsChanged": 4,
  "collectionEntriesAppended": 12,
  "fieldsRejected": 2
}
```

- `errors` carries the section 16 preview (at most five); the example array is abbreviated.
- `sourceRecords` counts CSV data records, excluding the header and skipped blank lines; for a completely evaluated file `acceptedRows + invalidRows + supersededRows = sourceRecords`.
- Superseded duplicate rows are reviewable errors (`DUPLICATE_ISIN_SUPERSEDED`) and are included in `errorCount`.
- While a job is `QUEUED`, `PROCESSING`, or `RETRY_PENDING`, `counts` and `completedAt` are `null`, `errorCount` is `0`, and `errors` is empty. `startedAt` is `null` until the first attempt starts.
- A `FAILED` job that could not be completely evaluated reports the counts it has and `null` for those it cannot determine.
- An unknown job ID returns `404` as `application/problem+json`.

## 21. Operational defaults

Agreed 2026-10-02.

| Item | Value |
|---|---|
| Schema migrations | Flyway SQL migrations in the repository, applied by a dedicated migration Lambda that the deployment workflow invokes before the new code version takes traffic; both schemas share one migration history |
| Retry delay | 1 minute before attempt 2, 5 minutes before attempt 3 (three total attempts, temporary failures only) |
| Attempt time limit | 15 minutes (the worker Lambda timeout); the queue visibility timeout is 16 minutes so a crashed invocation's message becomes visible again |
| Exhausted attempts | Job `FAILED`; message moves to a dead-letter queue with 14-day retention; later jobs in the same ordering group are released |
| Internal file-processing queue and DLQ | SQS FIFO, created and owned by this service's Terraform. The external security-details queue remains supplied (section 14.2) |
| Outbox dispatch | Attempted immediately after the creating transaction commits, when no older pending event exists in the same ordering group; a sweeper Lambda runs every minute and delivers anything still pending, in order. Failed sends retry indefinitely with exponential backoff, starting at 10 seconds and doubling to a cap of 5 minutes (start fixed 2026-10-03), preserving the original event |
| Expired or missing upstream object | Job `FAILED` with `SOURCE_NOT_FOUND`, no automatic retry; the fetch-service artifact bucket expires objects after 30 days, so replay beyond that window requires a new fetch run |

### 21.1 Retention and backups

Agreed 2026-10-02. Accepted business data in `securities_data` is retained indefinitely.

| Data | Location | Retention |
|---|---|---|
| Canonical run files | Processing-service S3 bucket | Indefinite |
| `ingestion_requests`, `processing_runs`, `source_files` | PostgreSQL | Indefinite (business rows reference the job ID) |
| `rejected_records`, `validation_issues` | PostgreSQL | 1 year, then deleted by a scheduled cleanup |
| Delivered `outbox_events` | PostgreSQL | Deleted 30 days after successful delivery; undelivered events are never purged |

After the one-year cleanup a job still reports its stored `errorCount` and counts, while the error preview and error list return no entries; the rejected records remain in the run's canonical file. RDS automated daily backups are retained for 7 days with deletion protection enabled.

### 21.2 Deployment sizing and network layout

Revised 2026-10-02 for the Lambda runtime (section 23).

| Component | Value |
|---|---|
| Compute | AWS Lambda, Java, arm64, 1024 MB, SnapStart enabled; no always-on compute |
| API | One API Gateway HTTP API with custom domain `processing.kagent.app` |
| Database | RDS PostgreSQL 16, `db.t4g.micro`, 20 GB gp3 storage, single availability zone |
| Network | One shared VPC `10.20.0.0/16` across two availability zones, one public and one private subnet in each |
| NAT gateway | One, shared by both zones |
| Load balancers | None |

Accepted trade-off: a single-zone database and a single NAT gateway mean an availability-zone outage stops the service until it recovers; data is protected by the daily backups. The VPC, subnets, NAT gateway, and endpoints belong to the `cloud-platform-network` repository, consumed by both this service and the fetch service.

## 22. Table definitions

Derived from the agreed decisions above (2026-10-02); the Flyway migrations implement exactly these. All timestamps are `TIMESTAMPTZ`; ISIN and other text columns are `TEXT`; unbounded numerics are `NUMERIC` without declared scale. Origin-reference columns (`source_request_id`, `source_file`, `source_location`) replace the earlier `canonical_record_id` (section 17.5); `source_location` is a CSV record number or a JSONPath.

### 22.1 `securities_data`

```sql
CREATE TABLE securities_data.securities (
  isin                   TEXT PRIMARY KEY,
  issuer_name            TEXT,
  issuer_ownership_type  TEXT,
  instrument_type        TEXT,
  allotment_date         DATE,
  redemption_date        DATE,
  original_face_value    NUMERIC CHECK (original_face_value >= 0),
  collateral_status      TEXT,
  asset_coverage_basis   TEXT,
  asset_coverage_value   NUMERIC,
  asset_coverage_unit    TEXT CHECK (asset_coverage_unit = 'PERCENT'),
  coupon_rate_value      NUMERIC,
  coupon_rate_unit       TEXT CHECK (coupon_rate_unit = 'PERCENT'),
  coupon_type            TEXT,
  listing_status         TEXT,
  field_sources          JSONB NOT NULL DEFAULT '{}',
  created_at             TIMESTAMPTZ NOT NULL,
  updated_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE securities_data.security_daily_market_summaries (
  isin              TEXT NOT NULL REFERENCES securities_data.securities(isin) ON DELETE RESTRICT,
  trade_date        DATE NOT NULL,
  exchange_name     TEXT NOT NULL,
  security_code     TEXT,
  open_price        NUMERIC CHECK (open_price >= 0),
  high_price        NUMERIC CHECK (high_price >= 0),
  low_price         NUMERIC CHECK (low_price >= 0),
  close_price       NUMERIC CHECK (close_price >= 0),
  traded_volume     NUMERIC CHECK (traded_volume >= 0 AND traded_volume = trunc(traded_volume)),
  number_of_trades  NUMERIC CHECK (number_of_trades >= 0 AND number_of_trades = trunc(number_of_trades)),
  turnover          NUMERIC CHECK (turnover >= 0),
  face_value        NUMERIC CHECK (face_value >= 0),
  source_request_id UUID NOT NULL,
  source_file       TEXT NOT NULL,
  source_location   TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (isin, trade_date, exchange_name)
);
CREATE INDEX ON securities_data.security_daily_market_summaries (trade_date, exchange_name, isin);
```

The four append-only tables share one pattern: UUID `id` primary key, `isin` foreign key with `ON DELETE RESTRICT`, the agreed business columns (all nullable), the three origin-reference columns, `first_recorded_at`, and one unique index over ISIN plus every business column declared `NULLS NOT DISTINCT` so empty values compare equal (section 13.4). Inserts use `ON CONFLICT DO NOTHING`.

| Table | Business columns in the unique index |
|---|---|
| `security_cash_flows` | `event_type`, `record_date DATE`, `due_date DATE`, `amount_payable NUMERIC`, `payment_date DATE`, `new_face_value NUMERIC` |
| `security_listings` | `exchange_name`, `listing_date DATE` |
| `security_ratings` | `source_category` (`CURRENT`/`EARLIER`, NOT NULL), `rating_agency_name`, `rating`, `outlook`, `rating_action`, `rating_date DATE`, `rating_change_date DATE`, `verification_date DATE` |
| `security_collateral_assets` | `asset_type`, `collateral_description`, `remarks` |

The unique index leads with `isin`, so lookups by ISIN use it and no separate index on `isin` is needed. `NUMERIC` equality treats `89400` and `89400.00` as the same value, satisfying the normalized-comparison rule.

### 22.2 `data_processing`

```sql
CREATE TABLE data_processing.ingestion_requests (
  id                        UUID PRIMARY KEY,              -- API jobId
  idempotency_key           TEXT NOT NULL UNIQUE,
  payload_hash              TEXT NOT NULL,                 -- detects key reuse with a different payload (409)
  dataset_urn               TEXT NOT NULL,
  subject                   TEXT NOT NULL,                 -- exchange/BSE/trade-date/2026-01-01 or isin/INE...
  ordering_group            TEXT NOT NULL,                 -- trade-date:{date} or isin:{isin}
  acceptance_sequence       BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
  event_source              TEXT NOT NULL,
  event_id                  TEXT NOT NULL,
  run_id                    TEXT NOT NULL,
  inputs                    JSONB NOT NULL,
  manifest_bucket           TEXT NOT NULL,
  manifest_key              TEXT NOT NULL,
  manifest_version_id       TEXT,
  dataset_fingerprint       TEXT NOT NULL,
  submission_event          JSONB NOT NULL,                -- immutable admission evidence
  source_contract_id        TEXT NOT NULL,
  source_contract_version   TEXT NOT NULL,
  source_contract_hash      TEXT NOT NULL,
  mapping_contract_id       TEXT NOT NULL,
  mapping_contract_version  TEXT NOT NULL,
  mapping_contract_hash     TEXT NOT NULL,
  status                    TEXT NOT NULL CHECK (status IN
    ('QUEUED','PROCESSING','RETRY_PENDING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
  attempt_count             INTEGER NOT NULL DEFAULT 0,
  counts                    JSONB,
  error_count               INTEGER NOT NULL DEFAULT 0,
  submitted_at              TIMESTAMPTZ NOT NULL,
  started_at                TIMESTAMPTZ,
  completed_at              TIMESTAMPTZ,
  UNIQUE (event_source, event_id)
);
CREATE INDEX ON data_processing.ingestion_requests (ordering_group, acceptance_sequence);

CREATE TABLE data_processing.processing_runs (
  id                    UUID PRIMARY KEY,
  ingestion_request_id  UUID NOT NULL REFERENCES data_processing.ingestion_requests(id),
  attempt_number        INTEGER NOT NULL,
  status                TEXT NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED_TEMPORARY','FAILED_PERMANENT')),
  failure_code          TEXT,
  failure_detail        TEXT,
  canonical_object_key  TEXT,
  started_at            TIMESTAMPTZ NOT NULL,
  completed_at          TIMESTAMPTZ,
  UNIQUE (ingestion_request_id, attempt_number)
);

CREATE TABLE data_processing.source_files (
  id                    UUID PRIMARY KEY,
  ingestion_request_id  UUID NOT NULL REFERENCES data_processing.ingestion_requests(id),
  fetch_job_id          TEXT,
  bucket                TEXT NOT NULL,
  object_key            TEXT NOT NULL,
  file_name             TEXT NOT NULL,                     -- basename; the only part exposed publicly
  format                TEXT NOT NULL,
  sha256                TEXT NOT NULL,
  size_bytes            BIGINT NOT NULL,
  last_modified         TIMESTAMPTZ,
  version_id            TEXT,
  UNIQUE (ingestion_request_id, bucket, object_key)
);

CREATE TABLE data_processing.rejected_records (
  id                 UUID PRIMARY KEY,
  processing_run_id  UUID NOT NULL REFERENCES data_processing.processing_runs(id) ON DELETE CASCADE,
  source_file_id     UUID REFERENCES data_processing.source_files(id),
  isin               TEXT,
  record_number      INTEGER,                              -- CSV
  json_path          TEXT,                                 -- JSON
  disposition        TEXT NOT NULL,                        -- QUARANTINED_INVALID, QUARANTINED_SUPERSEDED, FIELD_REJECTED, ...
  record             JSONB NOT NULL,                       -- the full canonical record, raw values included
  created_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE data_processing.validation_issues (
  id                   UUID PRIMARY KEY,                   -- API errorId
  processing_run_id    UUID NOT NULL REFERENCES data_processing.processing_runs(id) ON DELETE CASCADE,
  rejected_record_id   UUID REFERENCES data_processing.rejected_records(id) ON DELETE CASCADE,
  sequence_number      BIGINT NOT NULL,                    -- deterministic order within the run; cursor basis
  code                 TEXT NOT NULL,
  isin                 TEXT,
  source_file_name     TEXT,
  record_number        INTEGER,
  field                TEXT,
  json_path            TEXT,
  raw_value            JSONB,
  message              TEXT NOT NULL,
  action_taken         TEXT,
  created_at           TIMESTAMPTZ NOT NULL,
  UNIQUE (processing_run_id, sequence_number)
);
CREATE INDEX ON data_processing.validation_issues (processing_run_id, isin, sequence_number);
CREATE INDEX ON data_processing.validation_issues (created_at);   -- one-year cleanup
CREATE INDEX ON data_processing.rejected_records (processing_run_id);
CREATE INDEX ON data_processing.rejected_records (created_at);     -- one-year cleanup
CREATE INDEX ON data_processing.rejected_records (source_file_id);
CREATE INDEX ON data_processing.validation_issues (rejected_record_id);  -- cascade deletes

CREATE TABLE data_processing.outbox_events (
  id               UUID PRIMARY KEY,                       -- CloudEvent id for security-details events
  destination      TEXT NOT NULL CHECK (destination IN ('FILE_PROCESSING','SECURITY_DETAILS')),
  message_group    TEXT,
  ordering_key     BIGINT,                                 -- acceptance_sequence for FILE_PROCESSING
  payload          JSONB NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('PENDING','DELIVERED')),
  attempt_count    INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL,
  last_error       TEXT,
  created_at       TIMESTAMPTZ NOT NULL,
  delivered_at     TIMESTAMPTZ
);
CREATE INDEX ON data_processing.outbox_events (status, next_attempt_at) WHERE status = 'PENDING';
```

The final applicable attempt for public error review is the run with the highest `attempt_number` of a request. Business tables carry `source_request_id` without a cross-schema foreign key, so retention cleanup in `data_processing` never blocks or cascades into accepted data.

## 23. Serverless runtime architecture

Agreed 2026-10-02. The service runs on AWS Lambda and API Gateway instead of ECS Fargate and load balancers, to remove always-on compute cost and to process bursts in parallel. Processing rules, contracts, tables, and API shapes are unchanged.

### 23.1 Functions

One build artifact (a single Java application) is deployed as five Lambda functions that differ only in their handler. All run in the private subnets of the shared VPC, on arm64 with SnapStart.

| Function | Trigger | Responsibility | Timeout | Concurrency |
|---|---|---|---|---|
| API | API Gateway HTTP API | `POST /v1/event-ingestions` and the five read endpoints | 29 seconds | Reserved 10 |
| Worker | SQS FIFO processing queue, batch size 1 | Runs one job: source reading, stage 1, stage 2, publication | 15 minutes | Maximum 10 through the event source mapping |
| Outbox sweeper | EventBridge schedule, every minute | Delivers pending outbox events in order; fails jobs stuck beyond their attempt limit | 1 minute | 1 |
| Retention | EventBridge schedule, daily | The cleanup in section 21.1 | 5 minutes | 1 |
| Migration | Invoked by the deployment workflow | Runs Flyway | 5 minutes | 1 |

### 23.2 API Gateway and access

- One HTTP API with the custom domain `processing.kagent.app`.
- The five `GET` routes have no authorizer.
- `POST /v1/event-ingestions` uses AWS IAM authorization. The fetch service's delivery Lambda signs the request with SigV4; its role is granted `execute-api:Invoke` on that route only. An unsigned or unauthorized request is rejected by API Gateway with `403` before reaching the application. This replaces "reachable only through an internal load balancer".
- The request and response bodies of the submission API are unchanged. The only change for the fetch service is signing the request and calling the API Gateway hostname.
- API Gateway throttling protects the public read routes and the small database.

### 23.3 Queue, ordering, and retries

- The processing queue stays SQS FIFO with the message group set to the ordering group (`trade-date:{date}` or `isin:{isin}`). SQS delivers one message per group at a time, so jobs within a group run in order while different groups run in parallel.
- On a temporary failure the worker records the failed run, sets the message's visibility timeout to the agreed delay (1 minute, then 5 minutes), and reports failure. The group stays blocked until that message is processed, which preserves ordering through retries.
- On the third failed attempt the worker marks the job `FAILED` and reports failure; the queue's redrive policy (`maxReceiveCount` 3) moves the message to the dead-letter queue and the group continues.
- If an invocation dies without recording anything (crash or timeout), the message reappears after the visibility timeout and the next invocation starts a new run. The sweeper marks a job `FAILED` when it is still non-terminal after its third run has exceeded the attempt time limit.
- Agreed 2026-10-03: the processing queue and its dead-letter queue belong to this service and keep `maxReceiveCount` 3. A receive can end before an attempt is recorded (the database unreachable at the claim, or an invocation dying before it commits), so a message can reach the dead-letter queue while its job still waits to retry. The sweeper therefore also fails any job that has stayed `QUEUED`, `PROCESSING`, or `RETRY_PENDING` past a time limit, whatever its attempt count, so no job waits forever.
- Content failures are terminal on first occurrence: the worker records the outcome and acknowledges the message.

### 23.4 Outbox without a polling loop

A Lambda cannot run a background loop. After a transaction that created outbox events commits, the same invocation sends them to SQS before returning, provided no older pending event exists for the same ordering group; otherwise it leaves them for the sweeper. A failed send never fails the request: the `202` (or the job result) stands and the sweeper delivers the event within about a minute. FIFO deduplication uses the outbox event ID, so a send repeated by both paths is delivered once.

### 23.5 Database connections

Each Lambda execution environment holds one database connection. With API concurrency 10, worker concurrency 10, and the scheduled functions, the service uses at most about 25 connections, within the limit of a `db.t4g.micro`. RDS Proxy is not used. Raising concurrency requires revisiting this limit.

### 23.6 Deployment

The deployment workflow uploads the build artifact to S3, applies Terraform, publishes new function versions, invokes the migration function and stops on failure, then moves the live alias to the new versions. There is no container image and no health endpoint; CloudWatch alarms cover function errors, dead-letter queue depth, the age of the oldest pending outbox event, and API 5xx responses.

### 23.7 Consequences for data-fetch-service

Its Terraform currently requires the processor's internal load balancer and security group. It must instead consume this service's API endpoint and route ARN, sign the submission request, and grant its delivery role `execute-api:Invoke`. The shared network contract (VPC, subnets, NAT gateway, endpoints) is unchanged.
