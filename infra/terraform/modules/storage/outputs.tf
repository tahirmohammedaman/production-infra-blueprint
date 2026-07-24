output "bucket" {
  description = "Bucket name"
  value       = aws_s3_bucket.this.id
}

output "retention" {
  description = "Effective retention, for cross-checking against the Loki and backup configs"
  value = {
    logs    = var.log_retention_days
    backups = var.backup_retention_days
  }
}
