# DNS records pointing at the node's stable primary IPs.
#
# Proxying is off. Traefik terminates TLS with a certificate it obtains itself over ACME
# http-01, and a proxied record would answer that challenge with Cloudflare's edge instead —
# the certificate never issues and the failure reads as an unrelated TLS error. It also
# keeps the request path honest: what a reviewer measures is what the node served.

resource "cloudflare_dns_record" "a" {
  zone_id = var.zone_id
  name    = var.record_name
  type    = "A"
  content = var.ipv4
  ttl     = var.ttl
  proxied = false
  comment = "Managed by terraform - infra/terraform/modules/dns"
}

resource "cloudflare_dns_record" "aaaa" {
  zone_id = var.zone_id
  name    = var.record_name
  type    = "AAAA"
  content = var.ipv6
  ttl     = var.ttl
  proxied = false
  comment = "Managed by terraform - infra/terraform/modules/dns"
}
