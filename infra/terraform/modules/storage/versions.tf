# The AWS provider here talks to Hetzner's S3-compatible endpoint; the configuration lives
# in the calling environment, not in this module.
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
