# Infrastructure

Terraform for the production environment in `ap-south-1`, pinned to Terraform `~> 1.16.4` and AWS provider `6.61.0` with committed lock files. Nothing here has been applied; see [implementation status](../docs/plans/implementation-status.md).

| Directory | Owns |
|---|---|
| `bootstrap/` | The Lambda package bucket `data-processing-service-artifacts` |
| `persistent/` | RDS PostgreSQL 16, its subnet group, parameter group, security group, and log group; the canonical-file bucket `data-processing-service-canonical`. The database and bucket are `prevent_destroy` and outlive every application deployment |
| `application/` | The six Lambda functions and their IAM roles, the HTTP API at `processing.kagent.app` (every route IAM-authorized), the FIFO processing queue and its DLQ, schedules, alarms, and DNS. Disposable: destroying it keeps every byte of data |
| `modules/network/` | Nothing: a read-only view of the shared network state for this service |

The network (VPC, subnets, endpoints, Lambda security groups; no NAT gateway, so the functions reach only the VPC, S3, and SQS) is owned by [cloud-platform-network](https://github.com/maverickuser/cloud-platform-network). Never define network resources here.

## State

The deploy workflow creates the state bucket `data-processing-service-terraform-state` (versioned, encrypted, no public access) before any Terraform runs, because a root cannot keep its state in a bucket it creates. State is locked with an S3 lock file. Each root has its own key, `<root>/terraform.tfstate` (`bootstrap`, `persistent`, `application`):

```sh
terraform -chdir=infra/bootstrap init \
  -backend-config=bucket=data-processing-service-terraform-state \
  -backend-config=key=bootstrap/terraform.tfstate \
  -backend-config=region=ap-south-1 \
  -backend-config=use_lockfile=true
```

## Persistent resources

The database is `db.t4g.micro`, 20 GB gp3, single zone (the zone of the network's interface endpoints, so losing that zone never strands the database without SQS), encrypted, not publicly accessible, with seven days of backups, deletion protection, and a final snapshot. `rds.force_ssl` refuses connections without TLS, and the server certificate comes from `rds-ca-rsa2048-g1`, which the packaged `ap-south-1` bundle covers. IAM database authentication is on; the master password is generated and kept by RDS in Secrets Manager, so this repository creates and rotates no secret. Its security group admits PostgreSQL only from the network's `processing` Lambda security group and has no egress.

Outputs read by the application root: `database_address`, `database_port`, `database_name`, `database_resource_id` (for `rds-db:connect` ARNs), `database_master_secret_arn` (the migration function's only), `database_security_group_id`, `canonical_bucket`, `canonical_bucket_arn`.

A destroy takes the final snapshot `data-processing-service-final`; delete or rename an older snapshot of that name first. Destroying it takes three deliberate steps: remove `prevent_destroy`, turn off deletion protection, then destroy. No workflow does this.

## Application

Inputs: `hosted_zone_id`, `read_api_caller_role_arns` (the roles the read routes serve; empty serves nobody), `deployment_commit` (full SHA) and `package_sha256` (base64) of the package at `releases/{commit}/data-processing-service-lambda.zip` in the package bucket, and optionally `alert_email`. The fetch service's queue and bucket are plain inputs with their deployed names as defaults (`security_details_queue_name`, `source_bucket`, `source_key_prefix`), so this root applies before the fetch service. It reads the persistent state (`persistent/terraform.tfstate`) and the shared network.

| Function | Database role | AWS actions besides its logs and network interfaces |
|---|---|---|
| `read-api` | `processing_reader` | none |
| `submission-api` | `processing_submission` | `sqs:SendMessage` on the processing queue |
| `worker` | `processing_worker` | consume the processing queue; `sqs:SendMessage` on the security-details queue; `s3:GetObject`/`GetObjectVersion` on the fetch bucket's `runs/*`; `s3:PutObject` on the canonical bucket's `canonical/*` |
| `outbox-sweeper` | `processing_sweeper` | `sqs:SendMessage` on both queues |
| `retention` | `processing_retention` | none |
| `migration` | master user, from the secret | `secretsmanager:GetSecretValue` on the master secret only |

Every function role may `rds-db:connect` only as its own database role, except the migration role, which has no `rds-db:connect`. Terraform publishes a version on each change and creates the `live` alias once; the deploy workflow invokes the migration function's new version, then moves each alias to `published_versions`.

The processing queue's policy refuses `SendMessage` from every principal except the submission and sweeper roles, administrators included, so moving messages back from the dead-letter queue (`StartMessageMoveTask`) or a manual test send needs a temporary policy change.

On the first apply the aliases, the worker mapping, and the schedules are live before the migration has created the database roles, so the sweeper fails, and alarms, until the deploy workflow has migrated.

The reserved concurrency (read 10, submission 5, sweeper, retention, and migration 1 each) needs the account's Lambda concurrency quota to be at least 118, because AWS keeps 100 unreserved.

Every route uses IAM authorization. API Gateway refuses an unsigned request, or one from a caller without `execute-api:Invoke` on the route, with `403`. The read function then serves only the roles in `read_api_caller_role_arns`; grant each of them `execute-api:Invoke` on `read_route_arns`, then sign requests, for example `curl --aws-sigv4 "aws:amz:ap-south-1:execute-api" --user "$AWS_ACCESS_KEY_ID:$AWS_SECRET_ACCESS_KEY" -H "x-amz-security-token: $AWS_SESSION_TOKEN" https://processing.kagent.app/v1/processing-jobs/<jobId>`.

Outputs for the fetch service: `processor_api_endpoint`, `processor_submission_route_arn`, `source_reader_role_arns` (its `processor_reader_role_arns`), `security_details_sender_role_arns` (add to its `external_producer_role_arns`). For read callers: `read_route_arns`. For the deploy workflow: `function_names`, `published_versions`, `alias_name`, `file_processing_queue_url`.

## Shared network module

`modules/network` reads `cloud-platform-network-terraform-state` / `network/terraform.tfstate` and outputs `vpc_id`, `vpc_cidr`, `private_subnet_ids` (in zone order), `lambda_security_group_id` (the `processing` entry), and `database_zone` (the first of the network's `interface_endpoint_zones`). The plan fails when there are fewer than two private subnets, when the network has no `processing` Lambda security group, when a subnet or the group is outside the shared VPC or a subnet assigns public addresses, or when the network reports no endpoint zone or one without a private subnet.

## Local checks

`make check-infra TERRAFORM=/path/to/terraform` runs `fmt -check`, then `init -backend=false`, `validate`, and the mocked `terraform test` suite in every directory. It downloads the provider but needs no AWS credentials. CI runs it as stage 7.
