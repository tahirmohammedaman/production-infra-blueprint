# State lives in Hetzner Object Storage, not Terraform Cloud.
#
# Chicken-and-egg: this bucket cannot be created by the configuration that stores its state
# in it. It is created once, by hand or with the snippet in infra/terraform/README.md, and
# then never touched again.
#
# `use_lockfile` is what replaced the DynamoDB table for state locking — S3 conditional
# writes. Hetzner supports them, so there is no second service to run for locking.
terraform {
  backend "s3" {
    bucket = "blueprint-tfstate"
    key    = "prod/terraform.tfstate"
    region = "eu-central-1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    use_lockfile   = true
    use_path_style = true

    # The endpoint is not AWS, so every AWS-specific validation must be skipped or the
    # provider fails before it makes a single request.
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
  }
}
