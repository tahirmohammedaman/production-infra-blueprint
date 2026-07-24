variable "bucket_name" {
  description = "Bucket name; must be globally unique within the Hetzner endpoint"
  type        = string
}

variable "log_retention_days" {
  description = <<-EOT
    How long Loki chunks are kept. Must match Loki's own retention_period. 30 days covers
    the incident-review window; longer is a storage bill for data nobody reads.
  EOT
  type        = number
  default     = 30
}

variable "backup_retention_days" {
  description = "How long base backups and archived WAL are kept"
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days >= 7
    error_message = "Keep at least a week; a corruption discovered on Friday is often a week old."
  }
}
