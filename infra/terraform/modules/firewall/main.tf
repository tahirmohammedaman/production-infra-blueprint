# Default deny inbound. Everything reachable from the internet is listed here, and the list
# is deliberately short: two ports for the public site, and SSH restricted to named CIDRs.
#
# Hetzner Cloud firewalls are enforced outside the host, so a misconfigured nftables rule or
# a container publishing a port it should not cannot expose anything this file does not
# allow. That is the reason to use them instead of relying only on the node's own firewall:
# the two controls fail independently.
#
# The Kubernetes API is not exposed. k3s listens on 6443 bound to the private network, and
# operators reach it by SSH tunnel. A publicly reachable API server on a single-node cluster
# is a credential-stuffing target with no upside.

resource "hcloud_firewall" "this" {
  name   = "${var.name}-fw"
  labels = var.labels

  rule {
    description = "HTTP - redirected to HTTPS by Traefik, kept open for ACME http-01"
    direction   = "in"
    protocol    = "tcp"
    port        = "80"
    source_ips  = ["0.0.0.0/0", "::/0"]
  }

  rule {
    description = "HTTPS"
    direction   = "in"
    protocol    = "tcp"
    port        = "443"
    source_ips  = ["0.0.0.0/0", "::/0"]
  }

  rule {
    description = "SSH from admin networks only"
    direction   = "in"
    protocol    = "tcp"
    port        = "22"
    source_ips  = var.admin_cidrs
  }

  # ICMP is allowed on purpose. Blocking it breaks path-MTU discovery, which produces
  # connections that establish and then hang on the first large response — a failure mode
  # that costs hours to diagnose and buys nothing in return.
  rule {
    description = "ICMP"
    direction   = "in"
    protocol    = "icmp"
    source_ips  = ["0.0.0.0/0", "::/0"]
  }

  dynamic "rule" {
    for_each = var.extra_inbound_rules
    content {
      description = rule.value.description
      direction   = "in"
      protocol    = rule.value.protocol
      port        = rule.value.port
      source_ips  = rule.value.source_ips
    }
  }
}
