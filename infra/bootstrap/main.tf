variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

# The deployment workflow creates the Terraform state bucket before any Terraform runs, because
# a root cannot keep its state in a bucket it creates. This root owns only the bucket that holds
# the Lambda deployment packages, one immutable object per commit.
resource "aws_s3_bucket" "packages" {
  bucket        = "data-processing-service-artifacts"
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "packages" {
  bucket                  = aws_s3_bucket.packages.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "packages" {
  bucket = aws_s3_bucket.packages.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "packages" {
  bucket = aws_s3_bucket.packages.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "packages" {
  bucket = aws_s3_bucket.packages.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Packages of past commits stay available for rollback; only overwritten or deleted versions and
# abandoned uploads are removed.
resource "aws_s3_bucket_lifecycle_configuration" "packages" {
  bucket = aws_s3_bucket.packages.id

  rule {
    id     = "expire-replaced-packages"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.packages]
}

locals {
  tls_only_policy = {
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.packages.arn, "${aws_s3_bucket.packages.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  }
}

resource "aws_s3_bucket_policy" "tls" {
  bucket = aws_s3_bucket.packages.id
  policy = jsonencode(local.tls_only_policy)

  depends_on = [aws_s3_bucket_public_access_block.packages]
}

output "package_bucket" {
  value = aws_s3_bucket.packages.bucket
}
