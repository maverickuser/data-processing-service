# PostgreSQL 16 for accepted securities data and processing state (LLD sections 17 and 23). It
# outlives every application deployment: deletion protection, prevent_destroy, and a final
# snapshot. The functions log in with IAM tokens as their own roles; the master password is
# generated and kept by RDS in Secrets Manager, so this root creates and rotates no secret.

locals {
  database_identifier = "data-processing-service"
}

resource "aws_db_subnet_group" "database" {
  name        = local.database_identifier
  description = "Shared private subnets of cloud-platform-network"
  subnet_ids  = module.network.private_subnet_ids
}

# No egress rules: Terraform removes the default allow-all egress, and the database starts no
# connections of its own.
resource "aws_security_group" "database" {
  name        = "${local.database_identifier}-database"
  description = "PostgreSQL from the processing Lambda functions only"
  vpc_id      = module.network.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "from_functions" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from the processing Lambda security group"
  referenced_security_group_id = module.network.lambda_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_parameter_group" "postgres" {
  name        = local.database_identifier
  family      = "postgres16"
  description = "TLS required; connections logged"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }
}

# RDS would otherwise create this group with no expiry.
resource "aws_cloudwatch_log_group" "postgres" {
  name              = "/aws/rds/instance/${local.database_identifier}/postgresql"
  retention_in_days = 30
}

resource "aws_db_instance" "database" {
  identifier     = local.database_identifier
  engine         = "postgres"
  engine_version = "16"
  instance_class = "db.t4g.micro"
  db_name        = "data_processing"
  port           = 5432

  username                    = "processing_master"
  manage_master_user_password = true

  iam_database_authentication_enabled = true
  ca_cert_identifier                  = "rds-ca-rsa2048-g1"
  parameter_group_name                = aws_db_parameter_group.postgres.name

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.database.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false
  multi_az               = false
  availability_zone      = module.network.database_zone

  backup_retention_period    = 7
  backup_window              = "20:30-21:00"
  maintenance_window         = "sun:21:30-sun:22:30"
  auto_minor_version_upgrade = true
  copy_tags_to_snapshot      = true
  delete_automated_backups   = false

  enabled_cloudwatch_logs_exports = ["postgresql"]

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.database_identifier}-final"

  depends_on = [aws_cloudwatch_log_group.postgres]

  lifecycle {
    prevent_destroy = true
  }
}
