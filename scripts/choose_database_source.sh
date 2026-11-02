#!/usr/bin/env bash
# Print the snapshot the persistent root should create the database from, or nothing for an empty
# database. Only a database that Terraform does not hold yet is ever restored.
#
# Usage: choose_database_source.sh <file listing `terraform state list` of infra/persistent>
set -euo pipefail

: "${AWS_REGION:?AWS_REGION is required}"
state_list="${1:?usage: choose_database_source.sh <persistent state list file>}"
# Fixed by infra/persistent/database.tf (identifier and final_snapshot_identifier). Change both together.
database="data-processing-service"
snapshot="${database}-final"

# Messages go to stderr: stdout carries only the snapshot identifier.
fail() {
  echo "::error::$*" >&2
  exit 1
}

if grep -qx 'aws_db_instance.database' "$state_list"; then
  echo "The database is already in Terraform state; nothing to restore." >&2
  exit 0
fi

# A database Terraform does not know about must be imported, not shadowed by a restore of the same name.
if probe="$(aws rds describe-db-instances --db-instance-identifier "$database" --query 'DBInstances[0].DBInstanceStatus' --output text 2>&1)"; then
  fail "$database exists ($probe) but is not in Terraform state; import it before deploying."
elif ! grep -q 'DBInstanceNotFound' <<<"$probe"; then
  fail "Unable to look up $database: $probe"
fi

if status="$(aws rds describe-db-snapshots --db-snapshot-identifier "$snapshot" --snapshot-type manual --query 'DBSnapshots[0].Status' --output text 2>&1)"; then
  if [ "$status" != available ]; then
    fail "Snapshot $snapshot is $status; rerun the deployment once it is available."
  fi
  echo "Creating $database from snapshot $snapshot." >&2
  echo "$snapshot"
elif grep -q 'DBSnapshotNotFound' <<<"$status"; then
  echo "No snapshot $snapshot; creating an empty $database." >&2
else
  fail "Unable to look up snapshot $snapshot: $status"
fi
