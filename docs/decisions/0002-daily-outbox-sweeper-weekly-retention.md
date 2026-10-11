# 0002 — Daily outbox sweeper and weekly retention

Date: 2026-10-11
Status: accepted

## Context

The owner asked on 2026-10-11 for the outbox sweeper to run once a day instead of every minute, and for retention to run once a week instead of daily. Until then the sweeper was the prompt retry path for failed outbox sends and the prompt check for stuck jobs (LLD 23.3, 23.4).

## Decision

- The outbox sweeper runs at 22:00 IST (`cron(30 16 * * ? *)`). EventBridge retries a failed delivery twice, and Lambda retries a failed sweep twice.
- Retention runs on Sundays at 03:00 IST (`cron(30 21 ? * SAT *)`), with no retries; its batched deletes keep their progress, so a run that times out is caught up the next week.
- Sends right after a commit are unchanged, so the sweeper remains a backstop for events that the creating invocation could not send.

## Consequences

- A failed send, and every later event in its ordering group (job queue messages and new-security fetch requests), waits for the next 22:00 run: up to about 24 hours instead of about a minute. The 10-second to 5-minute backoff still spaces attempts, but the daily sweep is the next attempt in practice.
- A stuck job is failed only at the next 22:00 run, up to about 25 hours after it stopped progressing; the one-hour limit decides which jobs are stuck, not when they are failed.
- One sweep sends at most 500 events within 30 seconds; a larger backlog carries over to the next day.
- The `OldestPendingOutboxAge` alarm uses a one-day period and still alarms on missing data.
- LLD 21.1, 23.1, 23.3, 23.4 and 23.8, the plan, the status file, the mocked Terraform test, and the handler Javadoc describe the new cadence.
