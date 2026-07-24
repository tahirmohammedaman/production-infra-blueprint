# A private network for one node looks like ceremony, and mostly is — today.
#
# It exists for two concrete reasons rather than tidiness. k3s binds flannel to this
# interface, so cluster traffic never traverses the public NIC even when a second node
# joins; and the firewall rules below can then be written against 10.0.0.0/16 instead of
# against a public address that changes when the server is rebuilt.
#
# Cost: Hetzner private networks are free. This is the rare case where the future-proofing
# is not paid for in monthly euros.

resource "hcloud_network" "this" {
  name     = "${var.name}-net"
  ip_range = var.ip_range
  labels   = var.labels
}

resource "hcloud_network_subnet" "nodes" {
  network_id   = hcloud_network.this.id
  type         = "cloud"
  network_zone = var.network_zone
  ip_range     = var.subnet_range
}
