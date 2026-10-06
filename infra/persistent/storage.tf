# Canonical files: the committed stage-1 output of every processing attempt (LLD section 7). They
# are evidence kept across attempts and resubmissions, so they never expire and the bucket
# outlives application deployments.
resource "aws_s3_bucket" "canonical" {
  bucket        = "data-processing-service-canonical"
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "canonical" {
  bucket                  = aws_s3_bucket.canonical.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "canonical" {
  bucket = aws_s3_bucket.canonical.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "canonical" {
  bucket = aws_s3_bucket.canonical.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "canonical" {
  bucket = aws_s3_bucket.canonical.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Current canonical files are kept; only replaced versions and abandoned uploads are removed.
resource "aws_s3_bucket_lifecycle_configuration" "canonical" {
  bucket = aws_s3_bucket.canonical.id

  rule {
    id     = "expire-replaced-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.canonical]
}

locals {
  canonical_tls_only_policy = {
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.canonical.arn, "${aws_s3_bucket.canonical.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }, {
      Sid       = "DenyOutdatedTls"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.canonical.arn, "${aws_s3_bucket.canonical.arn}/*"]
      Condition = { NumericLessThan = { "s3:TlsVersion" = "1.2" } }
    }]
  }
}

resource "aws_s3_bucket_policy" "canonical" {
  bucket = aws_s3_bucket.canonical.id
  policy = jsonencode(local.canonical_tls_only_policy)

  depends_on = [aws_s3_bucket_public_access_block.canonical]
}
