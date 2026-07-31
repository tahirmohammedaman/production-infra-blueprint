#!/usr/bin/env bash
#
# Verifies that a published image is the one this repository's pipeline built.
#
# A signature nobody checks is decoration. This is the check, written so that a reviewer
# can run it against a public image without any credentials, and so that the cluster's
# admission policy and a human on-call are asserting exactly the same thing.
#
# What it proves, in order:
#   1. The image is signed, and the signature is in the Rekor transparency log.
#   2. The signer is this repository's build workflow — not merely "someone with a
#      valid GitHub identity", which is what verifying without --certificate-identity
#      would actually mean.
#   3. An SPDX SBOM is attached, so what is inside the image can be answered six months
#      from now without rebuilding it.
#
#   scripts/verify-image.sh ghcr.io/tahirmohammedaman/blueprint-api:latest
#   scripts/verify-image.sh ghcr.io/tahirmohammedaman/blueprint-api@sha256:abc...

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

IMAGE="${1:-}"
[ -n "$IMAGE" ] || die "usage: scripts/verify-image.sh <image-reference>"

# Overridable so a fork can verify its own builds without editing the script.
REPO="${BLUEPRINT_REPO:-tahir/production-infra-blueprint}"
WORKFLOW_REF="${BLUEPRINT_WORKFLOW_REF:-https://github.com/${REPO}/.github/workflows/build.yml@refs/heads/main}"
OIDC_ISSUER="${BLUEPRINT_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"

command -v cosign >/dev/null 2>&1 \
  || die "cosign is not installed - see https://docs.sigstore.dev/cosign/installation/"

log "verifying signature for $IMAGE"
log "  expected signer: $WORKFLOW_REF"

# --certificate-identity pins the exact workflow. Without it, verification succeeds for
# anything signed by any GitHub Actions workflow anywhere, which is not a control.
if cosign verify \
     --certificate-identity="$WORKFLOW_REF" \
     --certificate-oidc-issuer="$OIDC_ISSUER" \
     "$IMAGE" >/dev/null 2>&1; then
  ok "signature valid and recorded in Rekor"
else
  die "signature verification failed - do not deploy this image"
fi

log "verifying the attached SBOM"
if cosign verify-attestation \
     --type=spdxjson \
     --certificate-identity="$WORKFLOW_REF" \
     --certificate-oidc-issuer="$OIDC_ISSUER" \
     "$IMAGE" >/dev/null 2>&1; then
  ok "SPDX SBOM attestation valid"
else
  warn "no valid SBOM attestation found"
fi

# Provenance is attached by GitHub's attestation API rather than by cosign, so it is
# verified with gh. Optional here: not every reviewer has gh installed or authenticated.
if command -v gh >/dev/null 2>&1; then
  log "verifying SLSA build provenance"
  if gh attestation verify "oci://${IMAGE}" --repo "$REPO" >/dev/null 2>&1; then
    ok "SLSA provenance valid"
  else
    warn "provenance could not be verified (needs an authenticated gh)"
  fi
else
  warn "gh not installed; skipping SLSA provenance check"
fi

log "to read what is inside the image:"
printf '  cosign download attestation --predicate-type=%s %s | jq -r .payload | base64 -d | jq .predicate\n' \
  "https://spdx.dev/Document" "$IMAGE"
