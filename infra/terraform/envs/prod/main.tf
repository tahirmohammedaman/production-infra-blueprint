# Production environment: one ARM node, one private network, one firewall, one data volume,
# one bucket, two DNS records.
#
# Everything here is deliberately small. The cost argument in docs/cost-analysis.md is not
# that this is cheap because it is a toy — it is that a system with a queue, a cache, a
# database, three pillars of observability and a GitOps controller fits comfortably in
# €10-15/month when the sizing comes from measurement rather than from a managed service's
# default instance class.

locals {
  name = "blueprint-prod"

  labels = {
    environment = "prod"
    managed_by  = "terraform"
    project     = "production-infra-blueprint"
  }
}

provider "hcloud" {
  token = var.hcloud_token
}

provider "aws" {
  region     = "eu-central-1" # Ignored by Hetzner; the provider requires the field.
  access_key = var.object_storage_access_key
  secret_key = var.object_storage_secret_key

  endpoints {
    s3 = var.object_storage_endpoint
  }

  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_region_validation      = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}

module "network" {
  source = "../../modules/network"

  name         = local.name
  ip_range     = "10.0.0.0/16"
  subnet_range = "10.0.1.0/24"
  network_zone = "eu-central"
  labels       = local.labels
}

module "firewall" {
  source = "../../modules/firewall"

  name        = local.name
  admin_cidrs = var.admin_cidrs
  labels      = local.labels
}

module "node" {
  source = "../../modules/node"

  name                 = local.name
  server_type          = var.server_type
  location             = var.location
  datacenter           = var.datacenter
  admin_ssh_public_key = var.admin_ssh_public_key
  network_id           = module.network.id
  subnet_id            = module.network.subnet_id
  firewall_id          = module.firewall.id
  private_ip           = "10.0.1.10"
  data_volume_size     = var.data_volume_size
  labels               = local.labels
}

module "storage" {
  source = "../../modules/storage"

  bucket_name           = var.object_storage_bucket
  log_retention_days    = var.log_retention_days
  backup_retention_days = var.backup_retention_days
}

module "dns" {
  source = "../../modules/dns"

  zone_id     = var.cloudflare_zone_id
  record_name = var.hostname
  ipv4        = module.node.ipv4
  ipv6        = module.node.ipv6
}
