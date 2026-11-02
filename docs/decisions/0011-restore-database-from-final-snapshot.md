# 0011 — A recreated database starts from its final snapshot

Date: 2026-11-02
Status: accepted

## Context

On 2026-11-01 the whole stack was torn down to stop its cost: the application root, then the persistent database by hand (its final snapshot `data-processing-service-final` was taken), then the shared network. The deploy workflow would recreate the database empty, and the accepted securities data, jobs, and outbox would be lost unless someone remembered to restore the snapshot by hand.

## Decision

When the persistent root does not hold the database yet, the deploy workflow creates it from the manual snapshot `data-processing-service-final` if that snapshot exists. `scripts/choose_database_source.sh` decides:

- The database is in persistent state: nothing changes; no AWS call is made.
- A database named `data-processing-service` exists but is not in state: the deployment fails, so it is imported rather than shadowed.
- The snapshot exists and is `available`: `TF_VAR_restore_snapshot_identifier` is set and Terraform restores it with every setting of the root (deletion protection, RDS-managed master secret, IAM authentication, parameter group, backups).
- The snapshot exists in any other status, or a lookup fails for another reason: the deployment fails before applying.
- No snapshot: the database is created empty, as before.

`snapshot_identifier` is in `ignore_changes`, because changing it on an existing database would replace it.

## Consequences

- Flyway runs against the restored schema and applies only newer migrations; the per-function database roles come back with the data. The restored database has a new resource ID and master secret; the application root reads both from the persistent outputs.
- The snapshot is kept after a restore. The next teardown's final snapshot has the same name, so that snapshot must be deleted or copied to another name before the next teardown (infra README).
- The deployment role needs `rds:DescribeDBSnapshots` and `rds:RestoreDBInstanceFromDBSnapshot` (implementation context spec).
- LLD section 21.1 records the restore.
