# Ansible

Turns a bare Debian node into a k3s host. Terraform creates the machine; Ansible decides
what is true about it — and the split matters, because Terraform is good at "this resource
exists" and bad at "this file has this content", which is most of what hardening is.

```
site.yml                one play, four roles, order is load-bearing
inventory/prod.yml      one host; its address comes from `terraform output node_ipv4`
roles/base/             timezone, packages, journald caps, zram, unattended security upgrades
roles/hardening/        sshd, sysctls, module blacklist
roles/storage/          mounts the data volume by UUID at the local-path provisioner's root
roles/k3s/              installs the cluster with secrets encryption and API audit logging
```

## Running it

```bash
ansible-galaxy collection install -r requirements.yml
export BLUEPRINT_NODE_IP=$(cd ../terraform/envs/prod && terraform output -raw node_ipv4)

ansible-playbook site.yml --check --diff   # read it first
ansible-playbook site.yml
```

The kubeconfig lands at `infra/.kube/blueprint-prod.yaml` (gitignored), rewritten to point
at the node's private address. Reaching it needs a tunnel:

```bash
ssh -L 6443:10.0.1.10:6443 deploy@$BLUEPRINT_NODE_IP
KUBECONFIG=infra/.kube/blueprint-prod.yaml kubectl get nodes
```

## Decisions worth knowing about

- **Role order is not cosmetic.** `hardening` sets the four sysctls that
  `k3s --protect-kernel-defaults` refuses to start without, and `storage` must mount the
  volume at `/var/lib/rancher/k3s/storage` *before* k3s provisions its first
  PersistentVolume — otherwise the database ends up on the root disk and quietly does not
  survive a node rebuild.
- **The volume is mounted by UUID.** Hetzner's own automount writes an fstab entry keyed on
  a device path, which changes when a volume is detached and reattached. The node then boots
  with an empty data directory and every service starts cleanly against nothing.
- **The filesystem is only created on a genuinely blank device.** Guarded on `blkid`
  returning nothing. An unguarded `mkfs` in a playbook is a very efficient way to delete a
  database.
- **k3s is pinned to a patch version, not `stable`.** Re-running this playbook in six months
  should not silently upgrade the cluster.
- **Traefik is disabled and deployed through Flux instead.** The bundled one is configured by
  a `HelmChartConfig` that lives on the node, which is exactly the blind spot a GitOps
  repository must not have.
- **Secrets encryption and API audit logging are on.** Both are free and both are only
  useful if they were enabled before the incident.
- **SSH allow-lists are set here as well as in the cloud firewall.** The duplication is the
  point: the two controls fail independently.

## What is deliberately not here

No role applies Kubernetes manifests. Flux reconciles the cluster from `clusters/prod/`;
a playbook that also applied workloads would be a second deployment path with no drift
detection, racing the one that has it.

Postgres backups are a Kubernetes CronJob, not a systemd timer on the node — it needs the
database's own credentials and network identity, both of which live in the cluster.
