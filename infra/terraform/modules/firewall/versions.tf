# Child modules must declare the provider they use by full source address. Without this,
# Terraform assumes the hashicorp/ namespace and fails to resolve the provider at init.
terraform {
  required_version = ">= 1.10"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.66"
    }
  }
}
