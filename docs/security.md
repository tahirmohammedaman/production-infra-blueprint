# Security

What this repository protects, against whom, with which controls — and, as plainly, what it does
not protect. Every control listed is enforced by configuration or by a CI gate; a measure that
exists only in this document is marked as a gap.

## Scope and threat model

The API has no authentication. It is a demonstration surface: anyone who can reach the gateway can
create and delete items, by design. What this repository protects is everything around it — the
platform, the data's durability, the credentials, and the path from a commit to a running image.

| Threat | Examples | Primary controls |
| --- | --- | --- |
| An attacker on the internet | scanning, exploiting an exposed service | one open service (the gateway); the Kubernetes API and SSH not publicly reachable |
| A compromised dependency or CI action | a malicious release, a moved tag | pinning by SHA and digest; scans before signing; CI holds no cluster or cloud credential |
| Tampering between build and run | an image pushed to the registry by something other than CI | keyless signatures verified at admission, pinned to this repository's workflow |
| A compromised pod | a remote code execution in the API | non-root distroless images, read-only filesystems, no capabilities, default-deny networking in both directions |
| Leaked credentials | secrets in git history, in environment variables, in logs | SOPS in git, files not environment, secret scanning of the full history |
| Losing the data | a destructive migration, a bug, an operator mistake | continuous WAL archiving, nightly base backups, a restore drill every week |

## The edge

- **The cloud firewall admits 80 and 443 from anywhere, and 22 only from named admin networks.**
  The Terraform module rejects `0.0.0.0/0` for SSH (`infra/terraform/modules/firewall`). Port 80
  exists for the ACME HTTP-01 challenge and redirects everything else to HTTPS.
- **The Kubernetes API is not on the internet at all.** k3s binds it to the private interface; an
  operator reaches it through an SSH tunnel, which is why TCP forwarding is the one forwarding
  sshd allows.
- **Only `/api`, `/swagger-ui` and `/v3/api-docs` are routed.** The actuator runs on a separate
  management port that no Ingress or Traefik router references, so health detail and metrics are
  reachable only from inside the cluster, by the monitoring namespace.

## The node

Configured by Ansible (`infra/ansible/roles`), so every setting can be re-asserted by running the
playbook again:

- **SSH:** no root login, no passwords or keyboard-interactive authentication, three attempts, and
  `AllowUsers` scoped to the admin networks. The config is validated with `sshd -t` before it is
  written; a syntax error would lock everyone out of the only node.
- **Kernel:** reverse-path filtering, no ICMP redirects or source routing, SYN cookies; unused
  filesystems and network protocols with a history of local privilege escalation blacklisted.
- **Patching:** unattended security upgrades. The reboot they sometimes need is the reason the
  availability objective is 99.5% rather than 99.9%.
- **k3s:** `--secrets-encryption` (Secrets encrypted at rest in its datastore),
  `--protect-kernel-defaults`, a kubeconfig readable only by root, and an API audit policy that
  records every write and every read of a Secret — at metadata level, so the log never contains
  a secret's value.

## The cluster

- **The `restricted` Pod Security Standard is enforced** on the application namespace, not only
  audited: no root, no privilege escalation, no host namespaces, seccomp required. Every pod,
  including the backup and restore Jobs, satisfies it; the stock Postgres image runs as a fixed
  unprivileged uid from the start rather than dropping to one itself.
- **NetworkPolicy is default-deny in both directions**, then an allowlist per workload
  (`deploy/k8s/base/network`). The worker cannot reach Postgres. The gateway can reach only the
  API's HTTP port. Postgres, the backup Job and the restore Job may leave the cluster only on 443,
  to public addresses: every private, shared and link-local range is excluded, including the cloud
  metadata endpoint. Both halves were verified in a real cluster — a pod labelled as the API
  reached Postgres, and an unlisted pod got no response.
- **No ServiceAccount token is mounted** into any application pod; none of them talks to the
  Kubernetes API.
- **Unsigned images of ours do not run.** See [supply chain](#the-supply-chain).

## The workloads

- **Distroless, non-root, no shell.** The service images are a `jlink` runtime on
  `distroless/java-base:nonroot`, running as uid 65532. There is no shell or package manager for
  an attacker to use.
- **Read-only root filesystems and no capabilities** on every container this repository builds,
  and on the backup and restore Jobs. Postgres, Kafka and Redis keep writable root filesystems:
  their images write sockets and lock files outside their data volumes.
- **Secrets are files, never environment variables.** Spring reads them through `configtree:`,
  Postgres through `*_FILE`, rclone through a credentials file. An environment variable is
  inherited by every child process, visible in `/proc`, and printed by `docker inspect`. A semgrep
  rule fails the build on `System.getenv` in service code, and another on a plaintext `PASSWORD`
  variable in any manifest.
- **Errors do not describe the system.** Problem responses never carry a stack trace, a driver
  message or a column name; the pool's occupancy, when it is exhausted, goes to the log and not to
  the client.

## Data

- **Passwords everywhere over TCP.** `pg_hba.conf` requires SCRAM for every network connection,
  localhost included; only the Unix socket inside the container trusts, and anyone who can exec
  into it can read the password files anyway.
- **Checksums on every page** (`--data-checksums`), so corruption is detected rather than backed
  up. The restore drill runs `pg_amcheck` on every restore.
- **Backups leave the node continuously.** WAL is archived to object storage within five minutes,
  a base backup is taken nightly, and both are kept for 14 days by a lifecycle rule on a versioned
  bucket that Terraform refuses to destroy (ADR 0009).

## Secrets

- **Encrypted in git with SOPS and age.** Values are encrypted, keys and metadata are not, so a
  rotation shows up in a diff without revealing anything. Flux decrypts inside the cluster with an
  age key that exists in one Secret and in a password manager — never in the repository. One
  object per file: SOPS silently drops every document after the first.
- **Five credentials issued by third parties are created by hand**, from files so they never enter
  shell history (`deploy/k8s/README.md`).
- **Secret scanning covers the whole history,** not just the diff: a credential committed and
  removed is still a credential.

## The supply chain

ADR 0008 has the reasoning; these are the controls.

- **Every input is pinned.** Actions by 40-character SHA, enforced by `scripts/check-action-pins.sh`
  in CI and as a pre-commit hook; base images by digest; scanners, charts and CLIs by version.
  Renovate proposes the upgrades, because a pin nobody moves becomes a known vulnerability.
- **Everything is scanned before it is signed:** gitleaks, semgrep (community rules and five of
  this repository's own), hadolint, trivy on the configuration and on the built image, and checkov
  on the Terraform, the workflow files, and the Kubernetes manifests exactly as kustomize renders
  them. Every gate is an exit code; nothing reports into a dashboard and merges anyway. (checkov's
  own kustomize renderer crashed on this tree on some runs and hung on others — four overlays share
  the name `prod` — so a green result there had been luck. It scans what kustomize produces now.)
- **Every published image is signed** with cosign keyless, bound to the build workflow's OIDC
  identity and recorded in Rekor, and carries an SPDX SBOM attestation and SLSA build provenance.
- **The cluster verifies it.** Sigstore's policy-controller admits an image of ours into the
  application namespace only if its signature was made by `build.yml` in this repository, on `main`
  or a release tag (`deploy/k8s/infrastructure/configs/image-policy.yaml`). It fails closed: if the
  webhook cannot answer, the pod is not admitted. Tested in a kind cluster with the production chart
  values — a correctly signed image was admitted, an unsigned one refused, and an image validly
  signed by a *different* workflow refused with "none of the expected identities matched".

Two defects in this chain were found by testing it rather than reading it. The admission policy
that ADR 0008, the build workflow and `verify-image.sh` all relied on did not exist until it was
looked for. And once it did, it would have refused every image this repository publishes: cosign
3 stores signatures in the new bundle format by default, and the policy-controller release
available here verifies only the legacy format for image signatures ("bundle support for image
signatures is not yet implemented"). CI now signs in the legacy format; attestations stay in the
bundle format, which `cosign verify-attestation` reads either way.

## CI

- **Read-only by default.** Workflows declare `contents: read`; the few jobs that write — publishing
  an image, submitting the dependency graph — widen it for themselves.
- **No long-lived credentials.** Registry pushes use the job's token; signing uses its OIDC
  identity. There is no cloud key, kubeconfig or age key in any workflow; delivery is pulled by Flux
  (ADR 0003).

## Known gaps

Recorded here rather than left for someone to discover.

| Gap | Why it is acceptable for now | What closes it |
| --- | --- | --- |
| The API has no authentication | it is a demonstration surface with nothing to protect | an identity provider at the gateway (forward auth) or in the application |
| The API and the exporter connect as the Postgres superuser that owns the schema | one role keeps the migration story simple | an owner role for Flyway, a DML-only role for the API, `pg_monitor` for the exporter |
| Pod-to-pod traffic is not encrypted | every pod is on one node, behind default-deny policies | a service mesh, or TLS on Postgres, once there is a second node |
| Backups are readable by anyone holding the bucket key | the bucket is private and encrypted at rest by the provider; two credentials can reach it | an rclone `crypt` remote, with its key stored off-cluster |
| Reads of the backup bucket are not audited | the provider does not implement bucket access logging (`CKV_AWS_18` in `.checkov.yaml`) | a provider that does |
| The k3s audit log stays on the node | it rotates at 64 MB, three files, seven days; it survives a pod, not a lost node | ship it through Alloy |
| Images we do not build are admitted unsigned | they are pinned by digest or upstream tag and scanned where they run; none is signed by a key we could require | require their publishers' signatures where they exist |
| The Compose stack's hardening has no scanner | neither checkov nor trivy reads Compose files | the smoke test asserts it instead, which is weaker |
