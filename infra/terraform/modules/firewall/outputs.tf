output "id" {
  description = "Firewall id, for attaching to servers"
  value       = hcloud_firewall.this.id
}
