# PR review policy

Date: 2026-10-02. This is a project policy. It borrows the scope of Google's public [code review guidance](https://google.github.io/eng-practices/review/reviewer/looking-for.html) (design, functionality, complexity, tests, naming, comments, style, documentation) and the small-increment discipline of stacked diffs. It is not a claim of compliance with any company's internal process.

## Precedence

The LLD decides behaviour. `docs/engineering/java-standards.md` decides code style and structure. `docs/engineering/testing-strategy.md` decides what must be tested. A reviewer's personal preference is not a blocker when the code meets those three.

## What a PR must contain

- One increment from the implementation plan, targeting its parent branch.
- A description using `.github/pull_request_template.md`: what changed and why, parent PR, LLD sections, test-case IDs added, validation results, operational effect.
- Tests for every behaviour added, with their catalogue IDs.
- Updates to the LLD, contracts, OpenAPI files, test catalogue, and `implementation-status.md` when the change affects them.
- No unrelated refactoring. A needed refactor goes in its own PR first.

Keep PRs small enough to review in one sitting: about 400 changed lines of production code at most, tests excluded. If a PR grows past that, split it with `gh stack modify` or a new layer and record the split in the status file.

## Review sequence

1. **Author self-review.** Read the actual diff. Run `make fmt lint test-unit coverage-check test-integration check-contracts check-docs`.
2. **Automated checks.** CI runs the same commands and uploads test and coverage reports, also on failure.
3. **Reviewer pass.** A separate reviewer-agent session reads the diff against the LLD and the standards, checks out the head, and reruns the gates. It reviews failure paths, not only the happy path.
4. **Fix.** The author fixes valid findings and adds a regression test for each behavioural finding.
5. **Recheck.** The reviewer re-reviews the new head. Evidence from an older head is not carried over.
6. **Merge** the reviewed head, bottom-up through the stack, with all required checks green.

The agreed profile is agent-only review: an implementer agent and a separate reviewer agent. Human approval is not a prerequisite. Never record or imply a human approval that did not happen.

## Findings

Every finding has a file and line, a concrete failure scenario or the rule it violates, and a suggested fix or check. Label each:

| Label | Meaning | Blocks merge |
|---|---|---|
| `blocking` | Wrong behaviour, data loss or corruption, broken invariant, security problem, contract mismatch, missing required test | Yes |
| `required` | Violates a written rule in the standards | Yes |
| `suggestion` | Would improve the code; author may decline with a reason | No |
| `question` | Reviewer needs to understand something | Until answered |

## Reviewer checklist

### Design

- The change fits the package layout and dependency direction. Domain code has no framework imports.
- Each class has one responsibility that its name states. No class or method name hides behind `Manager`, `Helper`, `Util`, `Processor`, `handle`, or `process`.
- No abstraction without a second use or a testing need. No speculative configuration.
- Stage 1 and stage 2 stay separate: no parsing in mapping, no persistence decisions in canonicalization.

### Correctness against the LLD

- No `float` or `double`; no rounding; decimals compared with `compareTo`.
- CSV: nothing is published before the whole file is checked; accepted rows are written in one transaction with security inserts, outbox events, and final job status.
- JSON: no-value inputs erase nothing; a bad field rejects only itself; collections only append; scalar precedence uses `LastModified` then object key.
- `Unsecured` handling matches LLD 15.2.
- Admission commits request, receipt, pinned contracts, and dispatch intent together before `202`.
- No direct SQS send inside or before a transaction; outbox only. Event ID and time survive retries.
- Per-trade-date and per-ISIN ordering holds through retries.
- Only the final run's errors are exposed. Warnings are logged, not stored as issues.
- Public responses carry no S3 locations or source URLs.
- Error codes and JSON property names match the OpenAPI documents exactly.

### Readability

- A reader can follow each method without scrolling to other files for basic meaning.
- Names use the domain vocabulary from LLD section 15.
- Javadoc on public types and methods states the contract. Comments explain why, not what.
- No dead code, no commented-out code, no flag parameters, no deep nesting.

### Tests

- New behaviour has the catalogue cases the plan assigns to this PR, plus any edge the reviewer can name.
- Tests assert outcomes a caller can observe. They would fail if the behaviour were wrong.
- Unit tests use fakes and fixed clock/IDs; no sleeping, no real network.
- Integration tests cover the transaction boundary or adapter the PR introduces, including one injected failure.
- Coverage is strictly greater than 95% without new exclusions.

### Persistence and operations

- Migrations are new files, forward-only, and match LLD 22. No edit to a merged migration.
- SQL is schema-qualified; every query that filters or orders has a supporting index.
- Timeouts on every outbound call; resources closed; no unbounded in-memory collection of file contents.
- Logs carry `jobId`; no secrets or payloads logged.
- Terraform: persistent resources stay in the persistent root with `prevent_destroy`; the external security-details queue and the shared Route 53 zone are referenced, never managed.

### Security

- No credentials in source, fixtures, or logs.
- The submission route keeps IAM authorization in Terraform; read routes stay unauthenticated. Application code does not implement its own caller check.
- Lambda and API Gateway types appear only in the `lambda` package and adapters.
- IAM grants are scoped to the specific bucket prefixes and queue ARNs the service uses.
- YAML contracts cannot name anything outside the registered rule set.

## Stacked PRs

Stacks are managed with GitHub Stacked PRs (`gh stack`). Review each layer's diff against the layer below it, and note any assumption it inherits. Fixes go on the layer they belong to, followed by `gh stack rebase` and `gh stack push`; the reviewer then rechecks that layer and the integration points above it. After a parent merges, run `gh stack sync` and rerun checks before merging the next layer. Merge bottom-up with `gh stack merge`.

## CI

`ci.yml` on GitHub Actions is the record of the automated checks. It runs on every pull request regardless of base branch and uses no AWS credentials. A reviewer's approval requires a green run on the exact head reviewed.
