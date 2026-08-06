#!/bin/sh
# restore_command: fetch one archived WAL segment during recovery.
#
#   restore-wal.sh <file name> <destination path>
#
# Postgres asks for files that were never archived as a matter of course — the next timeline's
# history file, the segment after the last one — and treats a non-zero exit as "not available",
# which is how recovery knows it has reached the end of the archive. Those misses exit 1 quietly.
# Anything else, a credential or network failure above all, is printed to the server log,
# because a restore that stops early for that reason looks exactly like one that finished.

set -euo pipefail

RCLONE="${RCLONE:-/tools/rclone}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE must name the rclone remote and bucket}"

name="$1"
destination="$2"
compressed="$destination.gz"
errors="$destination.err"

trap 'rm -f "$compressed" "$errors"' EXIT

if ! "$RCLONE" copyto --quiet "$BACKUP_REMOTE/wal/$name.gz" "$compressed" 2>"$errors"; then
  if ! grep -qi 'not found' "$errors"; then
    cat "$errors" >&2
  fi
  exit 1
fi

gunzip -c "$compressed" > "$destination"
