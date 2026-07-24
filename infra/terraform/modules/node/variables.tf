variable "name" {
  description = "Server name and prefix for its associated resources"
  type        = string
}

variable "server_type" {
  description = "Hetzner server type. cax21 = 4 Ampere vCPU / 8 GB, sized from measured RSS."
  type        = string
  default     = "cax21"

  validation {
    condition     = startswith(var.server_type, "cax")
    error_message = "Only ARM (CAX) types are supported; the service images are built for linux/arm64."
  }
}

variable "image" {
  description = "Base OS image"
  type        = string
  default     = "debian-12"
}

variable "location" {
  description = "Hetzner location, e.g. fsn1, nbg1, hel1"
  type        = string
  default     = "fsn1"
}

variable "datacenter" {
  description = "Hetzner datacenter, e.g. fsn1-dc14. Must be inside var.location."
  type        = string
  default     = "fsn1-dc14"
}

variable "admin_user" {
  description = "Non-root account Ansible connects as"
  type        = string
  default     = "deploy"
}

variable "admin_ssh_public_key" {
  description = "Public key authorised for the admin user"
  type        = string
}

variable "network_id" {
  description = "Private network to join"
  type        = string
}

variable "subnet_id" {
  description = "Subnet the server depends on; passed to order the apply, not read"
  type        = string
}

variable "private_ip" {
  description = "Fixed private address, so k3s and the firewall rules can name it"
  type        = string
  default     = "10.0.1.10"
}

variable "firewall_id" {
  description = "Firewall to attach"
  type        = string
}

variable "data_volume_size" {
  description = "Size in GB of the volume holding Postgres data and Kafka logs"
  type        = number
  default     = 20

  validation {
    condition     = var.data_volume_size >= 10
    error_message = "Hetzner volumes start at 10 GB."
  }
}

variable "labels" {
  description = "Labels applied to every resource"
  type        = map(string)
  default     = {}
}
