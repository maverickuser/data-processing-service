# Tech debt

Known shortcuts, gaps, and stale material that no current PR fixes. Add an entry when a review or a PR leaves something knowingly unfinished; remove it in the PR that fixes it. Each entry links to where the gap is recorded. Accepted trade-offs (such as the single-zone database, LLD section 21.2) and deliberately deferred scope (LLD section 12) are not debt and stay where they are.

| Added | Item | Why it matters | Source |
|---|---|---|---|
| 2026-10-11 | No alert recipient: `alert_email` is unset, so the alarm topic has no subscriber | Every alarm (failed jobs, stuck jobs, outbox age, 5xx) fires silently | [Implementation context](implementation-context-spec.md), "Alert recipient" |
| 2026-10-18 | The read OpenAPI document has never been run through an OpenAPI validator; `make check-contracts` checks only YAML/JSON equivalence | A schema error would reach clients unnoticed | [Implementation context](implementation-context-spec.md), "Read API" |
| 2026-10-18 | LLD section 22 (table definitions) is maintained by hand next to the Flyway migrations, with no check that they agree | The LLD can drift from the real schema | [Implementation context](implementation-context-spec.md), "Table definitions" |
| 2026-10-18 | Design-phase wording ("Proposed …") remains in LLD sections 2–15 for behaviour that has since been built | Agents may treat built behaviour as open for change | [LLD](../specs/structured-file-processing-lld.md) |
| 2026-10-18 | data-fetch-service keeps copies of the LLD and the submission OpenAPI documents; they must be re-synced after any change here | Fetch-side agents read a stale processor design | data-fetch-service `docs/specs/processing-service-copies.md` |
