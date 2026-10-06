# Read by infra/application through its remote state.

output "database_address" {
  value = aws_db_instance.database.address
}

output "database_port" {
  value = aws_db_instance.database.port
}

output "database_name" {
  value = aws_db_instance.database.db_name
}

output "database_resource_id" {
  description = "The resource ID in rds-db:connect ARNs: arn:aws:rds-db:REGION:ACCOUNT:dbuser:RESOURCE_ID/ROLE."
  value       = aws_db_instance.database.resource_id
}

output "database_master_secret_arn" {
  description = "The RDS-managed master secret; only the migration function may read it."
  value       = one(aws_db_instance.database.master_user_secret).secret_arn
}

output "database_security_group_id" {
  value = aws_security_group.database.id
}

output "canonical_bucket" {
  value = aws_s3_bucket.canonical.bucket
}

output "canonical_bucket_arn" {
  value = aws_s3_bucket.canonical.arn
}
