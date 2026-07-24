output "id" {
  description = "Server id"
  value       = hcloud_server.this.id
}

output "ipv4" {
  description = "Stable public IPv4, kept across server rebuilds"
  value       = hcloud_primary_ip.ipv4.ip_address
}

output "ipv6" {
  description = "Stable public IPv6"
  value       = hcloud_primary_ip.ipv6.ip_address
}

output "private_ip" {
  description = "Address k3s binds to"
  value       = var.private_ip
}

output "data_volume_device" {
  description = "Stable by-id device path for the data volume, for the Ansible mount"
  value       = hcloud_volume.data.linux_device
}
