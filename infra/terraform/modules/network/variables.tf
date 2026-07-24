variable "name" {
  description = "Name prefix for every resource in this module"
  type        = string
}

variable "ip_range" {
  description = "CIDR for the whole private network"
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.ip_range, 0))
    error_message = "ip_range must be a valid CIDR block."
  }
}

variable "subnet_range" {
  description = "CIDR for the node subnet, inside ip_range"
  type        = string
  default     = "10.0.1.0/24"
}

variable "network_zone" {
  description = "Hetzner network zone; must contain the location the node is in"
  type        = string
  default     = "eu-central"
}

variable "labels" {
  description = "Labels applied to every resource"
  type        = map(string)
  default     = {}
}
