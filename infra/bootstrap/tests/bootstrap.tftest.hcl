# Mocked: no AWS credentials. Apply runs against the mock provider only.
mock_provider "aws" {
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::data-processing-service-artifacts" }
  }
}

run "package_bucket_is_private_versioned_and_encrypted" {
  command = apply

  assert {
    condition     = aws_s3_bucket.packages.bucket == "data-processing-service-artifacts" && !aws_s3_bucket.packages.force_destroy
    error_message = "The package bucket name is fixed and it must never be force-destroyed."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.packages.block_public_acls,
      aws_s3_bucket_public_access_block.packages.block_public_policy,
      aws_s3_bucket_public_access_block.packages.ignore_public_acls,
      aws_s3_bucket_public_access_block.packages.restrict_public_buckets,
    ])
    error_message = "Every public-access block must be on."
  }

  assert {
    condition     = one(aws_s3_bucket_ownership_controls.packages.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "ACLs must be disabled."
  }

  assert {
    condition     = one(aws_s3_bucket_versioning.packages.versioning_configuration).status == "Enabled"
    error_message = "Packages must be versioned."
  }

  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.packages.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Packages must be encrypted at rest."
  }

  assert {
    condition     = one(aws_s3_bucket_lifecycle_configuration.packages.rule).noncurrent_version_expiration[0].noncurrent_days == 30
    error_message = "Only replaced package versions may expire."
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.tls.policy).Statement[0].Effect == "Deny" && jsondecode(aws_s3_bucket_policy.tls.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false" && jsondecode(aws_s3_bucket_policy.tls.policy).Statement[0].Resource == ["arn:aws:s3:::data-processing-service-artifacts", "arn:aws:s3:::data-processing-service-artifacts/*"]
    error_message = "The bucket policy must refuse requests without TLS."
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.tls.policy).Statement[1].Effect == "Deny" && jsondecode(aws_s3_bucket_policy.tls.policy).Statement[1].Condition.NumericLessThan["s3:TlsVersion"] == "1.2"
    error_message = "The bucket policy must refuse TLS older than 1.2."
  }
}
