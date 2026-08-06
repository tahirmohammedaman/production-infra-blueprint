#!/bin/sh
# archive_command: ship one completed WAL segment to object storage.
#
#   archive-wal.sh <path relative to the data directory> <file name>
#
# Postgres runs this once per segment (and per timeline history and backup history file) and
# recycles nothing until it exits 0. A failure is retried, with the segment kept on disk, for as
# long as it keeps failing — the right behaviour, and the reason a broken archive shows up as a
# growing pg_wal and the PostgresWalArchivingFailing alert rather than as a hole in the backups.
#
# rclone is the static binary the pod's init container copies into /tools; the stock postgres
# image carries no S3 client. Written for busybox ash.

set -euo pipefail

RCLONE="${RCLONE:-/tools/rclone}"
: "${BACKUP_REMOTE:?BACKUP_REMOTE must name the rclone remote and bucket}"

path="$1"
name="$2"

# gzip -1: most of the saving is the zero-filled tail of a segment switched by archive_timeout,
# which any level compresses to nothing. Higher levels cost archiving latency for a few percent.
gzip -1 -c "$path" | "$RCLONE" rcat --quiet "$BACKUP_REMOTE/wal/$name.gz"
