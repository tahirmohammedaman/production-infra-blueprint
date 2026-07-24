variable "name" {
  description = "Name prefix for every resource in this module"
  type        = string
}

variable "admin_cidrs" {
  description = <<-EOT
    Networks allowed to reach SSH. Set this to something real; the default is a placeholder
    that is deliberately not 0.0.0.0/0, so an unconfigured apply locks you out rather than
    silently exposing the port.
  EOT
  type        = list(string)

  validation {
    condition     = !contains(var.admin_cidrs, "0.0.0.0/0")
    error_message = "admin_cidrs must not be 0.0.0.0/0; SSH is not a public service."
  }
}

variable "extra_inbound_rules" {
  description = "Additional inbound rules, e.g. a temporary port during a migration"
  type = list(object({
    description = string
    protocol    = string
    port        = string
    source_ips  = list(string)
  }))
  default = []
}

variable "labels" {
  description = "Labels applied to every resource"
  type        = map(string)
  default     = {}
}
