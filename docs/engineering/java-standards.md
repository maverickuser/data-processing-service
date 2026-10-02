# Java engineering standards

Date: 2026-10-02. These are this project's rules. They draw on published practice from Google, Apache projects, Uber, and Meta where a specific, public source exists; each borrowed rule names its source. This document does not claim compliance with, or endorsement by, any of those organisations, and it does not adopt any guide wholesale.

Where rules conflict, the order is: LLD (behaviour) → this document → the referenced external guides.

## References

| Source | What we take from it |
|---|---|
| [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) | Formatting, naming, Javadoc. Enforced by google-java-format |
| [Google Engineering Practices — code review](https://google.github.io/eng-practices/review/) | Small changes, what reviewers look for, review speed |
| [Google AIP-158](https://google.aip.dev/158) and [AIP-193](https://google.aip.dev/193) | Cursor pagination shape; structured, stable error codes |
| [Google Testing Blog — test sizes](https://testing.googleblog.com/2010/12/test-sizes.html) | Small/medium/large split used in the testing strategy |
| [Error Prone](https://errorprone.info/) (Google) | Compile-time bug patterns |
| [NullAway](https://github.com/uber/NullAway) (Uber) with [JSpecify](https://jspecify.dev/) annotations | Compile-time null safety |
| [Apache Kafka coding guidelines](https://kafka.apache.org/coding-guide) | Logging discipline, no swallowed exceptions, clear ownership of threads |
| [Apache Commons CSV](https://commons.apache.org/proper/commons-csv/) | RFC 4180 parsing; we do not write our own CSV parser |
| [Apache Beam dead-letter pattern](https://beam.apache.org/documentation/patterns/overview/) and Kafka Connect dead-letter queues | Bad records are routed aside with their reason; they never stop good records. This is our rejected-records design |
| Stacked diffs, as practised at Meta and described for [Sapling](https://sapling-scm.com/docs/introduction/) | One reviewable increment per PR, each building on the last |
| [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html) | Reliable event delivery without distributed transactions |
| [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) | Problem details for HTTP errors |
| *Effective Java*, 3rd edition (Bloch) | Immutability, static factories, minimal accessibility, exceptions |

## Architecture

**Ports and adapters, packaged by feature.** Each feature package (`admission`, `canonical`, `publication`, …) contains:

- `domain` — records, enums, and pure functions. No Spring, no AWS SDK, no JDBC imports.
- `application` — use cases and the interfaces (ports) they need, such as `SourceObjectReader` or `OutboxEventStore`.
- `adapter` — implementations of ports and inbound adapters: `web`, `persistence`, `aws`.

A separate top-level `lambda` package holds the five Lambda entry points. Each translates its event into one use-case call. Lambda and API Gateway types are allowed only there and in adapters.

Dependencies point inward only. ArchUnit tests fail the build when a `domain` class imports a framework type or when one feature reaches into another feature's `adapter` or `application.internal` package.

**A pipeline of small stages.** Processing a file is a sequence of single-purpose steps, each with one input type and one output type:

```text
SourceObject → CanonicalRecord (stage 1) → MappedRecord (stage 2) → PublicationResult
```

A stage does not know what comes before or after it. This is what makes each stage unit-testable without mocks.

**Rules are a closed set.** Each normalization and validation rule named in a contract (`trim`, `normalizeGroupedNumber`, `nonNegative`, …) is one small class implementing one interface, registered by name. Adding a rule means adding a class and a test, not editing a switch statement.

## Naming

Names are the main documentation. A reader should know what a class does from its name and what a method returns from its signature.

| Kind | Rule | Good | Avoid |
|---|---|---|---|
| Class | Noun or noun phrase naming the single responsibility | `CsvCanonicalizer`, `DuplicateIsinResolver`, `GroupedNumberNormalizer`, `SecurityPublisher` | `CsvProcessor`, `DataManager`, `TradeHelper`, `CommonUtils` |
| Interface (port) | The role it plays for the caller, no `I` prefix, no `Impl` suffix on implementations | `SourceObjectReader` / `S3SourceObjectReader` | `IReader`, `ReaderImpl` |
| Record / value | The thing it is | `Isin`, `Percent`, `TradeDate`, `CanonicalRecord`, `ValidationIssue` | `IsinDto`, `PercentVO`, `RecordData` |
| Method | Verb phrase; queries read as nouns or `is/has` | `canonicalize(file)`, `resolveWinners(rows)`, `isTerminal()` | `process()`, `handle()`, `doWork()`, `execute()` |
| Boolean | Reads as a statement | `hasMoreErrors`, `isSuperseded` | `flag`, `check`, `status` |
| Test class | Subject plus `Test` (unit) or `IT` (integration) | `GroupedNumberNormalizerTest`, `AdmissionApiIT` | `Tests1`, `MiscTest` |
| Test method | Behaviour, in a sentence | `rejectsIndianGroupingWithMisplacedComma()` | `test1()`, `testNormalize()` |
| Constant / enum value | `UPPER_SNAKE_CASE` | `COMPLETED_WITH_ERRORS` | |
| Package | Lowercase, singular, a feature or a layer inside a feature | `canonical.csv` | `utils`, `common`, `misc` |

Use the domain words fixed in LLD section 15. Do not introduce synonyms: it is `isin`, `tradeDate`, `exchangeName`, `collateralStatus` — not `securityId`, `date`, `exchange`, `securedFlag`. Database names are the snake_case forms in LLD section 22.

A name that needs `And` or `Or` describes a class or method with two jobs. Split it.

## Readability rules

- **Short methods with one level of abstraction.** A method either coordinates steps or does a step. Length is a prompt for review, not a hard limit; do not fragment coherent logic to satisfy a number.
- **No flag arguments.** `publish(rows, true)` becomes two clearly named methods or two classes.
- **Guard clauses over nesting.** Return or throw early; keep the main path unindented.
- **Immutable by default.** Records for data. Fields `final`. Collections exposed as unmodifiable. No setters on domain types.
- **Values, not primitives, for domain concepts.** `Isin`, `TradeDate`, `Percent`, `JobId` — each validates and normalizes itself in its constructor, so the rule lives in one place.
- **No nulls across boundaries.** Packages are `@NullMarked` (JSpecify). The annotation is not inherited by sub-packages, so every package has its own `package-info.java` declaring it. A method that may have no result returns `Optional`. A nullable parameter or field is annotated `@Nullable` and is the exception.
- **Constructor injection only.** No field injection, no `ApplicationContext` lookups, no static mutable state.
- **Comments say why.** Javadoc on every public type and method states the contract: what it returns, what it rejects, side effects, transactional and ordering guarantees. Inline comments explain a non-obvious reason, never restate the code.
- **No dead code, no commented-out code, no `TODO` without a linked issue.**

## Numbers, dates, and text

- `BigDecimal` and `BigInteger` for every source number. `float` and `double` are banned in production code (enforced by an ArchUnit/Error Prone rule). Construct from strings, never from floating-point literals.
- Compare decimals with `compareTo`, never `equals`, unless scale matters.
- `LocalDate` for business dates, `Instant` for recorded times. Response models never carry `OffsetDateTime` or `ZonedDateTime`: `Instant` is always serialised in UTC, the others keep their own offset. Time comes from an injected `java.time.Clock`; never call `Instant.now()` directly.
- Locale-independent case conversion: `toUpperCase(Locale.ROOT)`.
- UUIDs come from an injected supplier so tests are deterministic.

## Errors

- **Bad data is a result, not an exception.** Validation produces `ValidationIssue` values that are collected and returned. Exceptions are for things the code cannot continue past.
- Two exception families: `TemporaryFailureException` (retry the job) and `PermanentFailureException` (fail the job, no retry). The worker decides on type, not on message text.
- Never catch `Exception` or `Throwable` except at the worker's and web layer's outermost boundary, where it is logged once with the job ID and converted.
- Never swallow an exception. Never log and rethrow the same exception at every layer.
- HTTP errors are `application/problem+json` produced in one `@RestControllerAdvice`.
- Error codes (`INVALID_DECIMAL`, `CHECKSUM_MISMATCH`, …) are an enum. They are part of the public contract; renaming one is a breaking change.

## Persistence

- Spring `JdbcClient` with explicit SQL. No JPA/Hibernate: the design depends on `INSERT … ON CONFLICT … RETURNING`, `NULLS NOT DISTINCT` indexes, and precise transaction boundaries that should be visible in the code.
- SQL lives in the repository class that uses it, as text blocks. Table and column names are schema-qualified.
- A transaction is opened by the application-layer use case that needs atomicity, with `@Transactional` on that one method. Repositories do not open transactions.
- Schema changes are Flyway migrations only. A merged migration is never edited; add a new one.
- Batch writes in bounded chunks inside the single publication transaction.

## Concurrency and resources

- The code runs on Lambda: no background threads, schedulers, or polling loops. Everything an invocation starts, it finishes before returning.
- Initialisation must be safe for SnapStart: no connections, random seeds, or timestamps captured at init that would be wrong after a restore; open the database connection lazily or re-validate it on first use.
- One database connection per execution environment (pool size 1).
- Stream large inputs. Do not load a whole file into an object graph; canonical records are written and released as they are produced.
- Every stream, S3 response body, and JDBC resource is closed with try-with-resources.
- Every outbound call (S3, SQS, JDBC) has an explicit timeout.

## Logging

- SLF4J with structured key-values. Every log line in a job's scope carries `jobId` and `attemptNumber` through MDC.
- Log an event once, at the layer that handles it.
- `WARN` for the agreed warning-only cases (for example payload ISIN differing from filename ISIN). `ERROR` only when something needs attention.
- Never log secrets, presigned URLs, or whole payloads.

## Tooling

| Tool | Role | Gate |
|---|---|---|
| Spotless + google-java-format | Formatting | Build fails on unformatted code |
| Checkstyle (Google checks, with project suppressions listed in one file) | Style | Build fails |
| Error Prone + NullAway | Bug patterns, null safety | Compile fails |
| ArchUnit | Layering, feature privacy, no cycles, null-marked packages, banned floating point, system clock, and random IDs. It inspects fields, signatures, and calls; the types of local variables are not visible to it | Unit test fails |
| JaCoCo | Unit line coverage | Strictly greater than 95% |
| OWASP Dependency-Check and secret scanning | Supply chain | Findings triaged with a recorded reason |

Suppressions are narrow, sit next to the code, and state why the rule does not apply.

The coverage gate is `scripts/check_coverage.py`, not JaCoCo's own rule, because JaCoCo's minimum is inclusive and the gate must be strict. The only class excluded from coverage is `DataProcessingApplication`, the bootstrap class with no logic, which the context-load integration test exercises. Any further exclusion needs a decision record.

Every compiler warning fails the build (`-Werror`), which is what turns Error Prone's warning-level findings into failures.
