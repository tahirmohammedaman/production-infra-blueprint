#!/bin/sh
# Prepares a data directory for point-in-time recovery.
#
# It does not recover anything itself. It lays down a base backup, checks it against its
# manifest, and tells Postgres where to stop; the server performs the recovery the next time it
# starts, fetching WAL through restore-wal.sh, and promotes itself onto a new timeline when it
# reaches the target.
#
#   RECOVERY_TARGET_TIME=2026-08-06T21:14:00Z   stop at this instant: UTC, exactly this format
#   (unset)                                     replay everything archived: the latest state
#   BACKUP=20260806T023000Z                     start from this base backup rather than the
#                                               newest one that finished before the target
#
# Run with the server stopped: by the postgres-restore Job (deploy/k8s/restore) and by
# scripts/restore-drill.sh. The data directory it replaces is moved aside, never deleted.
# Written for busybox ash.

set -euo pipefail

RCLONE="${RCLONE:-/tools/rclone}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE must name the rclone remote and bucket}"
: "${PGDATA:?PGDATA must name the data directory to restore into}"
target="${RECOVERY_TARGET_TIME:-}"
chosen="${BACKUP:-}"

log() { printf '%s prepare-restore: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "$*" >&2; exit 1; }

# Base backups are named by the UTC instant they started, in a fixed-width form that sorts as
# text. The target is converted to the same form so the two compare without date arithmetic,
# which busybox does not do reliably across time zones.
cutoff=""
if [ -n "$target" ]; then
  case "$target" in
    [0-9][0-9][0-9][0-9]-[01][0-9]-[0-3][0-9]T[0-2][0-9]:[0-5][0-9]:[0-5][0-9]Z) ;;
    *) die "RECOVERY_TARGET_TIME must be UTC in the form 2026-08-06T21:14:00Z, got '$target'" ;;
  esac
  cutoff=$(printf '%s' "$target" | tr -d ':-')
fi

# The newest backup that finished before the target. One that finished after it cannot be used:
# recovery can only move forwards from a backup's end, never backwards from it.
if [ -z "$chosen" ]; then
  for candidate in $("$RCLONE" lsf --dirs-only "$BACKUP_REMOTE/backups/" | tr -d / | sort -r); do
    finished=$("$RCLONE" cat "$BACKUP_REMOTE/backups/$candidate/COMPLETE" 2>/dev/null \
      | sed -n 's/^finished=//p')
    if [ -z "$finished" ]; then
      log "skipping $candidate: its upload never completed"
      continue
    fi
    if [ -z "$cutoff" ] || expr "$finished" \< "$cutoff" >/dev/null; then
      chosen="$candidate"
      break
    fi
  done
fi
[ -n "$chosen" ] || die "no complete base backup finished before '${target:-now}'"
log "restoring from base backup $chosen, target ${target:-the end of the archive}"

# A ReadWriteOnce volume can be mounted by two pods on the same node, and this is a one-node
# cluster: nothing but this check stops a restore Job from rewriting the data directory under a
# running server. A server that crashed leaves the file behind too; FORCE=true is for that case,
# after confirming the server is really gone.
if [ -f "$PGDATA/postmaster.pid" ] && [ "${FORCE:-false}" != "true" ]; then
  die "$PGDATA/postmaster.pid exists: the server is running or did not shut down cleanly. Stop it first; FORCE=true once it is certainly stopped"
fi

if [ -d "$PGDATA" ] && [ -n "$(ls -A "$PGDATA")" ]; then
  aside="$PGDATA.pre-restore-$(date -u +%Y%m%dT%H%M%SZ)"
  mv "$PGDATA" "$aside"
  log "moved the existing data directory aside to $aside"
fi

# Downloaded next to the data directory: the same volume, which needs the space anyway.
staging="$(dirname "$PGDATA")/restore-staging"
rm -rf "$staging"
trap 'rm -rf "$staging"' EXIT
"$RCLONE" copy --quiet "$BACKUP_REMOTE/backups/$chosen" "$staging"

mkdir -p "$PGDATA"
chmod 700 "$PGDATA"
tar -xzf "$staging/base.tar.gz" -C "$PGDATA"
tar -xzf "$staging/pg_wal.tar.gz" -C "$PGDATA/pg_wal"

# Every file against the checksum recorded when the backup was taken, and the backup's own WAL
# parsed end to end. A corrupt backup fails here, in seconds, instead of partway through a
# recovery that then has to be started again from something else.
pg_verifybackup --quiet --manifest-path="$staging/backup_manifest" "$PGDATA"
log "base backup verified against its manifest"

touch "$PGDATA/recovery.signal"
{
  printf "# Written by prepare-restore.sh for the restore from %s.\n" "$chosen"
  if [ -n "$target" ]; then
    # A numeric offset, not the Z the target was given with. The server checks this setting while
    # reading its configuration at startup, before the time zone abbreviations that make Z mean
    # UTC are loaded, and refuses to start: "invalid value for parameter recovery_target_time".
    # Found by the restore drill; nothing short of starting a server would have shown it.
    printf "recovery_target_time = '%s+00'\n" "$(printf '%s' "${target%Z}" | tr T ' ')"
    printf "recovery_target_action = 'promote'\n"
  fi
} >> "$PGDATA/postgresql.auto.conf"

log "ready: start the server and it will recover, then promote"
