variable "hcloud_token" {
  description = "Hetzner Cloud API token (read-write)"
  type        = string
  sensitive   = true
}

variable "admin_cidrs" {
  description = "Networks allowed to reach SSH. Never 0.0.0.0/0 - the module rejects it."
  type        = list(string)
}

variable "admin_ssh_public_key" {
  description = "Public key authorised on the node"
  type        = string
}

variable "server_type" {
  description = "Hetzner server type. See modules/node for how this number was chosen."
  type        = string
  default     = "cax21"
}

variable "location" {
  description = "Hetzner location"
  type        = string
  default     = "fsn1"
}

variable "datacenter" {
  description = "Hetzner datacenter inside var.location"
  type        = string
  default     = "fsn1-dc14"
}

variable "data_volume_size" {
  description = "GB of block storage for Postgres data and Kafka logs"
  type        = number
  default     = 20
}

variable "hostname" {
  description = "Public hostname served by the gateway"
  type        = string
}

variable "cloudflare_api_token" {
  description = "Cloudflare token scoped to DNS edit on one zone"
  type        = string
  sensitive   = true
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone id for var.hostname"
  type        = string
}

variable "object_storage_endpoint" {
  description = "S3-compatible endpoint for the object store"
  type        = string
  default     = "https://fsn1.your-objectstorage.com"
}

variable "object_storage_bucket" {
  description = "Bucket for Loki chunks, WAL archive and backups"
  type        = string
}

variable "object_storage_access_key" {
  description = "Object storage access key"
  type        = string
  sensitive   = true
}

variable "object_storage_secret_key" {
  description = "Object storage secret key"
  type        = string
  sensitive   = true
}

variable "log_retention_days" {
  description = "Loki chunk retention; must match the Loki configuration"
  type        = number
  default     = 30
}

variable "backup_retention_days" {
  description = "Base backup and WAL retention"
  type        = number
  default     = 14
}
