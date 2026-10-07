# Mocked: no AWS credentials and no real network state. Plans only, because the database and the
# canonical bucket are prevent_destroy and a mocked apply could not be torn down.
mock_provider "aws" {
  override_during = plan

  mock_resource "aws_security_group" {
    defaults = { id = "sg-0dddddddddddddddd" }
  }

  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::data-processing-service-canonical" }
  }
}

override_module {
  target = module.network
  outputs = {
    vpc_id                   = "vpc-0123456789abcdef0"
    vpc_cidr                 = "10.20.0.0/16"
    private_subnet_ids       = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
    lambda_security_group_id = "sg-0123456789abcdef0"
    database_zone            = "ap-south-1a"
  }
}

run "database_is_private_encrypted_and_iam_authenticated" {
  command = plan

  assert {
    condition = (
      aws_db_instance.database.engine == "postgres" &&
      aws_db_instance.database.engine_version == "16" &&
      aws_db_instance.database.instance_class == "db.t4g.micro" &&
      aws_db_instance.database.allocated_storage == 20 &&
      aws_db_instance.database.storage_type == "gp3" &&
      !aws_db_instance.database.multi_az &&
      aws_db_instance.database.availability_zone == "ap-south-1a" &&
      aws_db_instance.database.db_name == "data_processing"
    )
    error_message = "The database must be single-AZ PostgreSQL 16 in the endpoint zone ap-south-1a on db.t4g.micro with 20 GB gp3, database data_processing."
  }

  assert {
    condition     = aws_db_instance.database.storage_encrypted && !aws_db_instance.database.publicly_accessible
    error_message = "The database must be encrypted and unreachable from the internet."
  }

  assert {
    condition     = aws_db_instance.database.iam_database_authentication_enabled && aws_db_instance.database.manage_master_user_password && aws_db_instance.database.password == null
    error_message = "Functions log in with IAM; RDS owns the master password and none is set here."
  }

  assert {
    condition     = aws_db_instance.database.deletion_protection && !aws_db_instance.database.skip_final_snapshot && aws_db_instance.database.backup_retention_period == 7 && !aws_db_instance.database.delete_automated_backups
    error_message = "The database must be protected from deletion, keep seven days of backups, and take a final snapshot."
  }

  assert {
    condition     = aws_db_instance.database.ca_cert_identifier == "rds-ca-rsa2048-g1"
    error_message = "The server certificate must chain to a CA in the packaged ap-south-1 bundle."
  }

  assert {
    condition     = { for p in aws_db_parameter_group.postgres.parameter : p.name => p.value }["rds.force_ssl"] == "1"
    error_message = "The database must refuse connections without TLS."
  }

  assert {
    condition = (
      { for p in aws_db_parameter_group.postgres.parameter : p.name => p.value }["log_connections"] == "1" &&
      { for p in aws_db_parameter_group.postgres.parameter : p.name => p.value }["log_disconnections"] == "1"
    )
    error_message = "Connections and disconnections must be logged."
  }

  assert {
    condition     = aws_db_instance.database.port == 5432 && aws_db_instance.database.auto_minor_version_upgrade && aws_db_instance.database.backup_window == "20:30-21:00" && aws_db_instance.database.maintenance_window == "sun:21:30-sun:22:30"
    error_message = "Port, minor upgrades, and the non-overlapping backup and maintenance windows are fixed."
  }

  assert {
    condition     = aws_db_subnet_group.database.subnet_ids == toset(["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"])
    error_message = "The database must use the shared private subnets."
  }

  assert {
    condition     = aws_cloudwatch_log_group.postgres.retention_in_days == 30 && aws_db_instance.database.enabled_cloudwatch_logs_exports == toset(["postgresql"])
    error_message = "PostgreSQL logs go to a log group that expires."
  }
}

run "database_admits_only_the_processing_functions" {
  command = plan

  assert {
    condition     = aws_security_group.database.vpc_id == "vpc-0123456789abcdef0" && length(aws_security_group.database.ingress) == 0 && length(aws_security_group.database.egress) == 0
    error_message = "The database security group has no inline rules and no egress."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.from_functions.referenced_security_group_id == "sg-0123456789abcdef0" &&
      aws_vpc_security_group_ingress_rule.from_functions.cidr_ipv4 == null &&
      aws_vpc_security_group_ingress_rule.from_functions.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.from_functions.from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.from_functions.to_port == 5432
    )
    error_message = "PostgreSQL is admitted only from the processing Lambda security group."
  }

  assert {
    condition     = aws_db_instance.database.vpc_security_group_ids == toset(["sg-0dddddddddddddddd"])
    error_message = "The database carries only its own security group."
  }
}

run "canonical_bucket_is_private_versioned_and_kept" {
  command = plan

  assert {
    condition     = aws_s3_bucket.canonical.bucket == "data-processing-service-canonical" && !aws_s3_bucket.canonical.force_destroy
    error_message = "The canonical bucket name is fixed and it must never be force-destroyed."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.canonical.block_public_acls,
      aws_s3_bucket_public_access_block.canonical.block_public_policy,
      aws_s3_bucket_public_access_block.canonical.ignore_public_acls,
      aws_s3_bucket_public_access_block.canonical.restrict_public_buckets,
    ])
    error_message = "Every public-access block must be on."
  }

  assert {
    condition     = one(aws_s3_bucket_ownership_controls.canonical.rule).object_ownership == "BucketOwnerEnforced" && one(aws_s3_bucket_versioning.canonical.versioning_configuration).status == "Enabled"
    error_message = "ACLs must be disabled and objects versioned."
  }

  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.canonical.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Canonical files must be encrypted at rest."
  }

  assert {
    condition     = length(one(aws_s3_bucket_lifecycle_configuration.canonical.rule).expiration) == 0 && one(aws_s3_bucket_lifecycle_configuration.canonical.rule).noncurrent_version_expiration[0].noncurrent_days == 30
    error_message = "Current canonical files never expire; only replaced versions do."
  }

  assert {
    condition = (
      jsondecode(aws_s3_bucket_policy.canonical.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false" &&
      jsondecode(aws_s3_bucket_policy.canonical.policy).Statement[1].Condition.NumericLessThan["s3:TlsVersion"] == "1.2" &&
      alltrue([for s in jsondecode(aws_s3_bucket_policy.canonical.policy).Statement : s.Effect == "Deny" && s.Resource == ["arn:aws:s3:::data-processing-service-canonical", "arn:aws:s3:::data-processing-service-canonical/*"]])
    )
    error_message = "The bucket policy only refuses requests without TLS 1.2 or later, on this bucket; it grants nothing."
  }
}

run "outputs_name_what_the_application_needs" {
  command = plan

  assert {
    condition     = output.database_name == "data_processing" && output.database_port == 5432 && output.canonical_bucket == "data-processing-service-canonical"
    error_message = "The application root reads these outputs."
  }
}
