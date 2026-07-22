#!/usr/bin/env bash
#
# Fails if any GitHub Action is referenced by anything other than a full commit SHA.
#
# A tag is a mutable pointer. `uses: some/action@v4` means "run whatever the owner of that
# repository decides v4 means at the moment our workflow starts", with our repository token
# in scope. Tags have been moved before, and it is the cheapest supply-chain attack there
# is against a project that never notices.
#
# Every pin must also carry a trailing `# vX.Y.Z` comment. The SHA is what is enforced; the
# comment is what makes the diff readable and is what Renovate rewrites when it proposes an
# upgrade. A pin nobody can read is a pin nobody will ever update.
#
# Run directly, via `make verify-pins`, or as a pre-commit hook.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

failures=0

check_file() {
  local file="$1" line_no=0 line ref

  while IFS= read -r line; do
    line_no=$((line_no + 1))

    # Only `uses:` lines, whether or not they are a list item.
    [[ "$line" =~ ^[[:space:]]*-?[[:space:]]*uses:[[:space:]]*(.+)$ ]] || continue
    ref="${BASH_REMATCH[1]}"

    # Composite actions living in this repository are referenced by path and move with the
    # commit that uses them, so there is nothing to pin.
    [[ "$ref" == ./* ]] && continue

    if [[ ! "$ref" =~ @[0-9a-f]{40}([[:space:]]|$) ]]; then
      printf '%s:%s: not pinned to a commit SHA\n    %s\n' "$file" "$line_no" "$ref" >&2
      failures=$((failures + 1))
      continue
    fi

    if [[ ! "$ref" =~ \#[[:space:]]*v?[0-9] ]]; then
      printf '%s:%s: pinned but missing a "# vX.Y.Z" comment\n    %s\n' "$file" "$line_no" "$ref" >&2
      failures=$((failures + 1))
    fi
  done < "$file"
}

shopt -s nullglob
files=(.github/workflows/*.yml .github/workflows/*.yaml .github/actions/*/action.yml)
shopt -u nullglob

[ ${#files[@]} -gt 0 ] || die "no workflow files found"

log "checking action pins in ${#files[@]} file(s)"
for file in "${files[@]}"; do
  check_file "$file"
done

if [ "$failures" -gt 0 ]; then
  die "$failures unpinned or unlabelled action reference(s)"
fi
ok "every action is pinned to a commit SHA with a readable version comment"
