# 1. One ARM node on Hetzner Cloud, running k3s

Date: 2026-07-04, recorded 2026-08-08
Status: Accepted

## Context

The system needs a real Kubernetes cluster: rolling deploys, readiness gating, NetworkPolicy,
Pod Security Standards and a GitOps controller are the things this repository exists to show,
and none of them can be shown on a PaaS. It also has to be cheap enough to leave running, and
the cost has to be an argument rather than a footnote.

The options, for a workload that measures about 1.2 GB resident for the application and 0.6 GB
for its observability stack:

| Option | Control plane | Nodes | What it means here |
| --- | --- | --- | --- |
| EKS / GKE / AKS | $73 a month on EKS before a single node; comparable elsewhere | managed node groups | The control plane alone costs several times the node this workload needs. |
| A managed Kubernetes from a smaller provider | free or cheap | small VMs | Real, but the control plane is theirs to version, configure and audit-log. |
| Three self-managed nodes | k3s or kubeadm | three VMs | High availability — for a workload that has no replicas of its database, broker or cache to be highly available with. |
| **One self-managed node, k3s** | none to pay for | one ARM VM | The whole system on one machine, with the price of that stated honestly. |

## Decision

One Hetzner Cloud CAX21 — four Ampere vCPUs, 8 GB — running k3s, provisioned by Terraform and
configured by Ansible. Postgres data and Kafka logs live on a Hetzner volume that outlives the
server; a primary IP outlives it too, so a rebuilt node keeps its address.

k3s rather than kubeadm because it is a single binary with its datastore built in, and upgrading
it is replacing that binary. ARM rather than x86 because the price per core is lower and the CI
pipeline builds arm64 images on native runners, so the choice costs nothing downstream.

## Consequences

**The node is a single point of failure, and the objectives say so.** The availability objective
is 99.5%, not 99.9%, because one server cannot promise more than its host, and a kernel update
that needs a reboot takes every replica down at once (`docs/slo.md`). Going higher is an
architecture change — more nodes, and replicas of the stores — not a configuration change.

**Recovery is rebuild-and-restore, not failover.** Terraform recreates the node, Ansible
configures it, Flux reconciles everything from git, and the database comes back from object
storage (ADR 0009). The volume normally survives a node rebuild and makes the restore
unnecessary; the restore exists for when it does not.

**Everything shares one kernel and one disk.** Resource requests are set on every container, and
sized from measurements, because nothing else stops one workload starving another. The dev
environment is a namespace on the same node rather than a second cluster, which is the single
largest saving available and the reason its requests are deliberately smaller than production's.

**The price moves.** Hetzner raised CAX prices by roughly 30% on 15 June 2026; the CAX21 went
from €7.99 to €10.49 a month. The comparison in `docs/cost-analysis.md` survives it with a wide
margin, but it is a reminder that a cheap provider is a price, not a promise, and the Terraform
estimate has to be kept current by hand.

**When to revisit:** when the availability objective has to rise above what one host can give,
or when measured load no longer fits a CAX31. Either way the next step is three nodes and
replicated stores, which is where a managed control plane starts to be worth its fee.
