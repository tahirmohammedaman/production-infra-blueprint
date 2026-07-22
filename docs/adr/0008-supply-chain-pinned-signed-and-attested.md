# 8. Every input is pinned, every output is signed

Date: 2026-07-22
Status: Accepted

## Context

CI has write access to the container registry and, transitively, decides what runs in
production. It is the highest-privilege component in this repository, and it executes code
that nobody in this repository wrote: third-party actions, base images, a hundred-odd
transitive Java dependencies.

Two questions follow from that. What is CI allowed to run, and how does anything downstream
know that what it is about to run came from CI at all?

## Decision

**Inputs are pinned by immutable identifier.** Actions are referenced by 40-character commit
SHA with a version comment; base images by `sha256` digest; scanners by explicit version.
`scripts/check-action-pins.sh` fails the build on any reference that is not, and Renovate
proposes the upgrades so the pins stay current rather than merely frozen.

**Outputs are signed and attested.** Every published image is signed with cosign keyless,
carries an SPDX SBOM attestation, and carries SLSA build provenance — all attached to the
image in the registry, not to the CI run.

**CI holds no cluster credentials.** Its last act is to publish a signed image. Flux
reconciles from the repository; nothing pushes to the cluster.

## Consequences

### Why SHA pins rather than tags

`uses: some/action@v4` means "run whatever that repository's owner decides `v4` points at,
at the moment our workflow starts, with our repository token in scope." Tags are mutable and
have been moved. A SHA cannot be moved.

The cost is that pins go stale silently, which is a worse failure than the one being
prevented — a digest-pinned base image is perfectly reproducible and, two years later,
perfectly vulnerable. Pinning and Renovate are one control, not two, and adopting either
without the other is worse than adopting neither.

### Why keyless signing

Keyless binds the signature to the workflow's OIDC identity and records it in Rekor. There
is no private key to store, rotate, or leak, and no cost — the alternative, a KMS-backed
key, means paying for a KMS and then protecting access to it, which is the same problem one
layer down.

The cost is that verification is meaningless unless the expected identity is named.
`cosign verify` without `--certificate-identity` succeeds for anything signed by any GitHub
Actions workflow anywhere, which reads like a control and is not one.
`scripts/verify-image.sh` names the identity, and the cluster's admission policy asserts
the same string.

### Why the image is pushed before it is scanned

A multi-arch manifest cannot be assembled without pushing its parts. So the published image
is scanned after it exists, and the gate is the signature rather than the push: an image
that fails the scan is never signed, and an unsigned image is inert because admission
requires a valid signature. An unsigned image sitting in the registry is not a deployment.

### Why dependency CVEs are scanned in the image, not the source tree

Trivy cannot resolve a Gradle build script. Pointed at this repository it reports
"number of language-specific files: 0" and exits zero — a green check that means nothing was
examined, which is worse than no check at all because it is indistinguishable from a pass.

Dependencies are therefore scanned inside the built image, where they are real files, before
signing. That also covers the base layer, which source scanning never would. A weekly
scheduled run re-scans what is already published, because most vulnerabilities are not
introduced by a commit — they are disclosed after the last one.

### What this does not cover

The scanners run against what the build produces, not against the build. A compromised
action still executes with a repository token before anything is signed; pinning narrows
that to "the specific commit we reviewed", which is a real reduction and not an elimination.
Nothing here verifies that the SHA we pinned was itself built from the source it claims.

`ubuntu-24.04-arm` runners are used to build the arm64 image natively. Under QEMU the same
build takes over thirty minutes, because Gradle and jlink are both CPU-bound; the native
runner is the difference between a pipeline that runs on every push and one that gets
disabled.
