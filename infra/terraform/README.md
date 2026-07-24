# Terraform

Provisions everything the cluster runs on: one ARM node, its network, firewall, data volume,
object storage and DNS. It does **not** install anything on the node — that is Ansible's
job, and the split is deliberate: Terraform is good at "this resource exists with these
properties" and bad at "this file has this content".

```
modules/
  network/   private network + subnet; k3s binds flannel here
  firewall/  default-deny inbound, enforced outside the host
  node/      CAX (Ampere) server, stable primary IPs, data volume, cloud-init
  storage/   S3-compatible bucket with the lifecycle rules that decide the storage bill
  dns/       A and AAAA records, unproxied so ACME http-01 works
envs/prod/   the only environment; wires the modules together
```

## First run

State lives in a bucket that this configuration cannot create, because it would need
somewhere to keep the state describing it. Create it once:

```bash
export AWS_ACCESS_KEY_ID=...     # Hetzner object storage credentials
export AWS_SECRET_ACCESS_KEY=...
aws --endpoint-url https://fsn1.your-objectstorage.com \
    s3 mb s3://blueprint-tfstate
aws --endpoint-url https://fsn1.your-objectstorage.com \
    s3api put-bucket-versioning --bucket blueprint-tfstate \
    --versioning-configuration Status=Enabled
```

Versioning on the state bucket is not optional. It is the only thing standing between a
corrupted state file and rebuilding the inventory by hand.

Then:

```bash
cd envs/prod
export TF_VAR_hcloud_token=...
export TF_VAR_cloudflare_api_token=...
export TF_VAR_object_storage_access_key=...
export TF_VAR_object_storage_secret_key=...

terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

Secrets are passed as `TF_VAR_*` rather than written into `terraform.tfvars` so they never
land in a file, a shell history entry, or a CI log.

## Notes that will save an hour

- **`admin_cidrs` cannot be `0.0.0.0/0`.** The variable validation rejects it. An
  unconfigured apply should lock you out, not open SSH to the internet.
- **The AWS provider is pointed at Hetzner.** No AWS account is involved. Every
  AWS-specific validation is skipped in `main.tf`; without those flags the provider fails
  before it makes a request.
- **`aws_s3_bucket_public_access_block` and `aws_s3_bucket_acl` are absent on purpose.**
  Hetzner returns 501 for both. Buckets are private by default and access is granted with
  scoped credentials.
- **The volume has `prevent_destroy`.** It holds the database. Removing it is a two-step
  operation on purpose.
- **The server ignores changes to `image`.** An OS upgrade that silently rebuilt the node
  would take the k3s state with it; upgrades are done deliberately, with the restore drill
  in `docs/runbooks/` as the safety net.

## What this deliberately does not do

No CI pipeline runs `terraform apply`. Cloud credentials that can create servers are the
most valuable secret in the system, and the delivery model here — Flux pulling from git —
exists specifically so that CI never needs them. Infrastructure changes are applied by a
human from a workstation, and the plan is read before it is applied.
