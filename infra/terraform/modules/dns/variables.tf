variable "zone_id" {
  description = "Cloudflare zone the records belong to"
  type        = string
}

variable "record_name" {
  description = "Fully qualified record name, e.g. blueprint.example.com"
  type        = string
}

variable "ipv4" {
  description = "Target IPv4 address"
  type        = string
}

variable "ipv6" {
  description = "Target IPv6 address"
  type        = string
}

variable "ttl" {
  description = <<-EOT
    Record TTL in seconds. Short by default: the primary IP is stable, so a low TTL costs
    nothing in practice and is what makes an emergency repoint take minutes instead of a day.
  EOT
  type        = number
  default     = 300
}
