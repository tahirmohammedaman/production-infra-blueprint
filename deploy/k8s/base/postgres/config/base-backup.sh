#!/bin/sh
# Takes a base backup of the running server and uploads it to object storage.
#
# Run nightly by the postgres-backup CronJob, and by scripts/restore-drill.sh. On its own a base
# backup restores the database to the moment it finished; with the WAL archive-wal.sh ships
# continuously, it is the starting point for a restore to any moment after that
# (prepare-restore.sh).
#
#   backups/<started>/base.tar.gz       the data directory
#   backups/<started>/pg_wal.tar.gz     the WAL written while it was taken, so the backup is
#                                       consistent even without the archive
#   backups/<started>/backup_manifest   checksums of every file, checked by pg_verifybackup
#   backups/<started>/COMPLETE          written last; a backup without it is never restored
#
# Needs PGHOST, PGUSER and the password in $POSTGRES_PASSWORD_FILE. Written for busybox ash.

set -euo pipefail

RCLONE="${RCLONE:-/tools/rclone}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE must name the rclone remote and bucket}"
: "${PGHOST:?PGHOST must name the server to back up}"
: "${PGUSER:?PGUSER must name a role with the REPLICATION attribute}"
workdir="${BACKUP_WORKDIR:-/backup}"
password_file="${POSTGRES_PASSWORD_FILE:-/run/secrets/postgres-password}"

log() { printf '%s base-backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

name=$(date -u +%Y%m%dT%H%M%SZ)
target="$workdir/$name"
started=$(date +%s)

# pg_basebackup takes its password from a pgpass file, not from a file holding only the
# password, which is what the Secret provides. Written into the scratch directory, owner-only,
# and removed with it.
umask 077
pgpass="$workdir/.pgpass"
printf '*:*:*:*:%s\n' "$(cat "$password_file")" > "$pgpass"
export PGPASSFILE="$pgpass"
trap 'rm -rf "$pgpass" "$target"' EXIT

log "starting $name from $PGHOST"
# --checkpoint=fast: the backup starts now rather than at the next scheduled checkpoint, up to
# five minutes away. The I/O spike that costs is the price of a job that finishes predictably.
pg_basebackup \
  --pgdata="$target" \
  --format=tar \
  --gzip \
  --wal-method=stream \
  --checkpoint=fast \
  --label="$name" \
  --no-password

size=$(du -sk "$target" | cut -f1)
log "uploading $name (${size} KiB)"
"$RCLONE" copy --quiet "$target" "$BACKUP_REMOTE/backups/$name"

# Last, so an upload interrupted halfway leaves a backup prepare-restore.sh will not choose.
finished=$(date -u +%Y%m%dT%H%M%SZ)
printf 'finished=%s\nbytes=%s\n' "$finished" "$((size * 1024))" \
  | "$RCLONE" rcat --quiet "$BACKUP_REMOTE/backups/$name/COMPLETE"

log "backup $name complete in $(( $(date +%s) - started ))s, finished=$finished"
