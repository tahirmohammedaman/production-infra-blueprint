output "id" {
  description = "Network id, for attaching servers"
  value       = hcloud_network.this.id
}

output "subnet_id" {
  description = "Subnet id"
  value       = hcloud_network_subnet.nodes.id
}

output "ip_range" {
  description = "CIDR of the whole private network, for firewall rules"
  value       = hcloud_network.this.ip_range
}
