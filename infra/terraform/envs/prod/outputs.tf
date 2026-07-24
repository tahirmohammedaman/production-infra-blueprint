output "node_ipv4" {
  description = "Public IPv4 - the address Ansible connects to and DNS points at"
  value       = module.node.ipv4
}

output "node_ipv6" {
  description = "Public IPv6"
  value       = module.node.ipv6
}

output "node_private_ip" {
  description = "Private address k3s binds to"
  value       = module.node.private_ip
}

output "data_volume_device" {
  description = "Stable device path for the data volume, consumed by the Ansible mount task"
  value       = module.node.data_volume_device
}

output "object_storage_bucket" {
  description = "Bucket used by Loki and the backup job"
  value       = module.storage.bucket
}

# The cost argument, computed rather than asserted. These are Hetzner's published list
# prices (EUR, excl. VAT, as of July 2026); they are not fetched from an API, so they are as
# current as the last person to read the pricing page. The point is that the number moves
# when the infrastructure moves, instead of living in a README that goes stale silently.
output "estimated_monthly_eur" {
  description = "Rough monthly cost of everything in this configuration"
  value = {
    node            = var.server_type == "cax21" ? 7.55 : null
    primary_ipv4    = 0.60
    data_volume     = var.data_volume_size * 0.048
    object_storage  = 5.99
    private_network = 0.00
    dns             = 0.00
    total_hint      = "node + ipv4 + volume + object storage; see docs/cost-analysis.md for the comparison"
  }
}
