output "fqdn" {
  description = "The name that was published"
  value       = cloudflare_dns_record.a.name
}
