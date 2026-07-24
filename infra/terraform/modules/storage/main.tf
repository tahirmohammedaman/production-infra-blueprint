# Object storage for Loki chunks, Postgres WAL archive and backups.
#
# Managed with the AWS provider pointed at Hetzner's S3-compatible endpoint, because there
# is no first-class hcloud resource for buckets. That is not a workaround so much as the
# normal way to manage any S3-compatible store, and it means the lifecycle policy — which is
# where the storage bill is actually decided — is code rather than a console setting nobody
# remembers changing.
#
# Why Hetzner Object Storage and not S3: €5.99/month for 1 TB with 1 TB of egress included.
# The equivalent on S3 is roughly $23 for storage plus $90 per TB egressed. Loki reads
# chunks back on every query over old data, so egress is not a rounding error here.

resource "aws_s3_bucket" "this" {
  bucket = var.bucket_name

  # Buckets holding backups are the one thing that must survive a `terraform destroy` typo.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id

  versioning_configuration {
    # Versioning is what makes an accidental delete or a ransomware overwrite recoverable.
    # It also means expired objects need explicit noncurrent-version rules below, or the
    # bucket grows forever and the cost argument quietly stops being true.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  # Loki chunks: kept for the retention window, then deleted. This must agree with Loki's
  # own `retention_period`; if the bucket keeps objects Loki has forgotten, you pay to store
  # data nothing can read.
  rule {
    id     = "loki-chunks"
    status = "Enabled"

    filter {
      prefix = "loki/"
    }

    expiration {
      days = var.log_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }

  # WAL archive: only needed back to the oldest base backup that is still useful. Keeping
  # more WAL than base backups is a common and expensive mistake — the WAL is worthless
  # without a base backup old enough to replay it onto.
  rule {
    id     = "wal-archive"
    status = "Enabled"

    filter {
      prefix = "wal/"
    }

    expiration {
      days = var.backup_retention_days
    }
  }

  rule {
    id     = "base-backups"
    status = "Enabled"

    filter {
      prefix = "backups/"
    }

    expiration {
      days = var.backup_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  # Bucket-wide, unfiltered, and separate from the three rules above on purpose.
  #
  # An interrupted multipart upload leaves parts that do not appear in a bucket listing and
  # are billed anyway — the classic "why is the bucket larger than the sum of its objects"
  # invoice. A base backup is exactly the kind of large upload that gets interrupted.
  #
  # It is its own rule rather than an `abort_incomplete_multipart_upload` block inside each
  # prefixed rule because a single unfiltered rule covers prefixes nobody has thought of
  # yet, which is the behaviour actually wanted. (It also happens to be the only shape
  # checkov's CKV_AWS_300 recognises: its parser does not find the block in a rule that
  # carries a `filter`. Reproduced against checkov 3.3.8 with a two-resource test case.)
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# Deliberately absent: aws_s3_bucket_public_access_block and aws_s3_bucket_acl. Hetzner
# Object Storage buckets are private unless a policy says otherwise, and the endpoint
# returns 501 Not Implemented for both of those APIs — an apply that includes them fails
# every time. Access is granted by scoped credentials, not by bucket ACLs.
