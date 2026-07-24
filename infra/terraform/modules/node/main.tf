# The node the whole system runs on.
#
# Sizing is derived from measurement, not from a round number. docs/cost-analysis.md records
# ~1.14 GB resident for the six application containers, and the observability stack adds
# roughly 700 MB (Prometheus, Loki, Alloy, Grafana, Tempo). A cax21 — 4 Ampere vCPU, 8 GB —
# leaves about 6 GB of headroom, which is what Kafka's page cache and Postgres' shared
# buffers actually want. cax11 (2 vCPU, 4 GB) fits at rest and dies under load.
#
# ARM rather than x86 for cost: cax21 is roughly 40% cheaper per vCPU than the equivalent
# cx line, and both service images are built for linux/arm64 in CI, so nothing about this
# choice is theoretical.

resource "hcloud_ssh_key" "admin" {
  name       = "${var.name}-admin"
  public_key = var.admin_ssh_public_key
  labels     = var.labels
}

# A primary IP outlives the server it is attached to. Rebuilding the node — a kernel that
# will not boot, a botched upgrade, a restore drill — then does not mean waiting out a DNS
# TTL, and the firewall and DNS records never need editing.
resource "hcloud_primary_ip" "ipv4" {
  name        = "${var.name}-ipv4"
  type        = "ipv4"
  location    = var.location
  auto_delete = false
  labels      = var.labels
}

resource "hcloud_primary_ip" "ipv6" {
  name        = "${var.name}-ipv6"
  type        = "ipv6"
  location    = var.location
  auto_delete = false
  labels      = var.labels
}

resource "hcloud_server" "this" {
  name         = var.name
  server_type  = var.server_type
  image        = var.image
  datacenter   = var.datacenter
  ssh_keys     = [hcloud_ssh_key.admin.id]
  firewall_ids = [var.firewall_id]
  labels       = var.labels

  public_net {
    ipv4_enabled = true
    ipv4         = hcloud_primary_ip.ipv4.id
    ipv6_enabled = true
    ipv6         = hcloud_primary_ip.ipv6.id
  }

  network {
    network_id = var.network_id
    ip         = var.private_ip
  }

  # cloud-init does the minimum needed for Ansible to take over: a non-root user with the
  # admin key, a Python interpreter, and SSH hardened before the first login. Everything
  # else is Ansible's job, because cloud-init runs exactly once and is therefore the wrong
  # place for anything that has to stay true.
  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    hostname      = var.name
    admin_user    = var.admin_user
    admin_ssh_key = var.admin_ssh_public_key
  })

  # The subnet must exist before the server can join the network.
  depends_on = [var.subnet_id]

  lifecycle {
    # Rebuilding the node on an image change would destroy the k3s state and the volume
    # attachment. Image upgrades are done by rebuilding deliberately, not by an apply that
    # was meant to change a label.
    ignore_changes = [image, ssh_keys]
  }
}

# Postgres' data directory lives here rather than on the server's own disk so that it
# survives a node rebuild. automount is off on purpose: Hetzner's automount writes an fstab
# entry keyed on a device path, which is not stable across reattachment. Ansible mounts it
# by UUID instead.
resource "hcloud_volume" "data" {
  name     = "${var.name}-data"
  size     = var.data_volume_size
  location = var.location
  format   = "ext4"
  labels   = var.labels

  lifecycle {
    prevent_destroy = true
  }
}

resource "hcloud_volume_attachment" "data" {
  volume_id = hcloud_volume.data.id
  server_id = hcloud_server.this.id
  automount = false
}
