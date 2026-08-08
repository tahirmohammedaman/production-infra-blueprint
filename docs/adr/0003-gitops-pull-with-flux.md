# 3. Delivery is pulled by Flux, never pushed by CI

Date: 2026-07-04, recorded 2026-08-08
Status: Accepted

## Context

Something has to turn a merged commit into a running change. The conventional answer is a
deploy step at the end of CI with a kubeconfig in a repository secret. That makes CI — which
runs third-party actions and every transitive build dependency — the most privileged thing that
can touch production, and it means the cluster's state is whatever the last successful pipeline
left behind, not what git says.

## Decision

The cluster pulls. Flux v2 runs inside it, watches this repository, and reconciles what it finds
under `clusters/prod/`. CI's last act is publishing a signed image to GHCR; Flux's image
automation notices the new tag, rewrites it in `deploy/k8s/overlays/prod`, and commits that
change back to `main`, where the same reconciliation applies it.

Flux rather than Argo CD because the node is small and the job is narrow. Argo CD brings a UI, a
repository server, a Redis and an application controller; Flux is a handful of controllers with no
UI, driven entirely by resources in git. The cost thesis applies to our own tooling too.

## Consequences

**No cluster credential exists outside the cluster.** No workflow holds a kubeconfig, a cloud
token or an age key. A compromised CI run can publish an image; it cannot deploy one that has not
been signed by this repository's workflow (ADR 0008), and it cannot touch anything else.

**Git describes what is running, including the image tag.** Image automation commits every tag
change, so `git log deploy/k8s/overlays/prod` is the deploy history, and a rollback is a revert
(`docs/runbooks/rollback.md`). Drift is corrected: a change made with `kubectl` survives only
until the next reconciliation, unless the Kustomization is suspended.

**Ordering is data.** Six Flux Kustomizations with `dependsOn`, `wait: true` and health checks
express what a deploy script would otherwise encode: controllers, then issuers, then namespaces
and secrets, then the stores, then the migration Job, then the application. A migration that fails
stops the rollout before any new pod starts.

**Secrets are encrypted in git and decrypted by Flux.** SOPS with an age key held in one Secret in
the cluster and in a password manager. Five credentials issued by third parties are created by
hand instead; they are listed in `deploy/k8s/README.md`.

**The costs.** Deploys are not instant: image automation polls every five minutes and reconciles
every ten, so a merge takes up to fifteen minutes to arrive, which is acceptable here and would
not be for a team shipping hot fixes hourly. A failed reconciliation is quieter than a failed
pipeline — it is a condition on a resource, not a red build — so `make flux-status` and the
Kustomizations' health checks are part of every incident triage. And the bot commits image tags
into `main`, which a branch protection rule has to allow.
