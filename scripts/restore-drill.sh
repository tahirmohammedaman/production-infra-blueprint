#!/usr/bin/env bash
#
# Proves the backups restore, instead of assuming they do: continuous WAL archiving and a base
# backup into an S3 object store, the database destroyed, then a point-in-time restore — failing
# unless the restored server holds every row committed before the chosen instant, none committed
# after it, passes a checksum and index verification, and resumes archiving on its new timeline.
#
# The parts that decide whether a restore works are production's: the same postgresql.conf and
# pg_hba.conf, the same four scripts, the same Postgres image and rclone binary, the same uid,
# and a real S3 API — rclone's own server stands in for Hetzner Object Storage. What differs is
# the scheduler. The cluster starts these containers from a StatefulSet, a CronJob and a Job that
# mount the same files at the same paths; here they are started directly.
#
#   make restore-drill                 run it; everything it creates is removed afterwards
#   ROWS=200000 make restore-drill     a larger dataset, for a restore time worth quoting
#   KEEP=true make restore-drill       leave the containers running to inspect
#
# Needs docker or podman and nothing else.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh
export LC_ALL=C

ROWS="${ROWS:-20000}"
KEEP="${KEEP:-false}"
PREFIX=blueprint-restore-drill
BUCKET=blueprint-drill
# The Compose stack's Postgres and the rclone release the manifests pin, both by digest.
POSTGRES_IMAGE=docker.io/library/postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
RCLONE_IMAGE=docker.io/rclone/rclone:1.75.0@sha256:b06aed988cf5967de7c25be5925240983981c757f4ed1ac9d2fa659d51d60548
# The StatefulSet's runAsUser and runAsGroup, so file ownership behaves as it does in the cluster.
RUN_AS=999:999
CONFIG="$PWD/deploy/k8s/base/postgres/config"
MIGRATIONS="$PWD/services/api/src/main/resources/db/migration"
WORK="$PWD/.rendered/restore-drill"
PGDATA=/var/lib/postgresql/data/pgdata

CONTAINER=$(container_cmd)
c() { "$CONTAINER" "$@"; }
now() { printf '%s' "${EPOCHREALTIME:-$(date +%s)}"; }
since() { awk -v start="$1" -v end="$(now)" 'BEGIN { printf "%.1f", end - start }'; }

remove_all() {
  c rm -f "$PREFIX-objstore" "$PREFIX-primary" "$PREFIX-restored" >/dev/null 2>&1 || true
  c volume rm -f "$PREFIX-tools" "$PREFIX-data" "$PREFIX-restored-data" >/dev/null 2>&1 || true
  c network rm "$PREFIX" >/dev/null 2>&1 || true
}
cleanup() {
  if [ "$KEEP" = "true" ]; then
    warn "KEEP=true: $PREFIX-* left running; remove them with: KEEP=false $0 --cleanup"
  else
    remove_all
  fi
}
if [ "${1:-}" = "--cleanup" ]; then
  remove_all
  exit 0
fi

sql() {  # sql <container> [psql arguments...]
  c exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U blueprint -d blueprint -tA "${@:2}"
}
new_volume() {  # an empty volume owned by the Postgres uid, as fsGroup makes it in the cluster
  c volume create "$1" >/dev/null
  c run --rm --user 0 -v "$1:/v" --entrypoint chown "$POSTGRES_IMAGE" "$RUN_AS" /v
}
start_server() {  # start_server <container> <volume>
  c run -d --name "$1" "${common[@]}" \
    -e POSTGRES_DB=blueprint -e POSTGRES_USER=blueprint \
    -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password \
    -e POSTGRES_INITDB_ARGS=--data-checksums \
    -e PGDATA="$PGDATA" \
    -v "$2:/var/lib/postgresql/data" \
    "$POSTGRES_IMAGE" postgres -c config_file=/etc/postgresql/postgresql.conf >/dev/null
}
wait_for_server() {  # over TCP: during first initialisation the entrypoint's temporary server
  local name="$1"    # answers on the socket only, and is not the server that will be tested
  for _ in $(seq 1 180); do
    if [ "$(c inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" != "true" ]; then
      c logs --tail 40 "$name" >&2 || true
      die "$name exited"
    fi
    if c exec "$name" pg_isready -q -h 127.0.0.1 -U blueprint -d blueprint 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  c logs --tail 40 "$name" >&2
  die "$name did not accept connections"
}
insert_batch() {  # insert_batch <container> <batch> <rows>
  sql "$1" -c "INSERT INTO items (id, name, description, quantity)
               SELECT gen_random_uuid(), '$2-' || g, repeat('x', 200), g % 100
               FROM generate_series(1, $3) AS g;"
}
count_batch() { sql "$1" -c "SELECT count(*) FROM items WHERE name LIKE '$2-%';"; }
wait_archived() {  # wait_archived <container> <segment>: until the archiver has shipped it
  local last
  for _ in $(seq 1 60); do
    last=$(sql "$1" -c "SELECT coalesce(last_archived_wal, '') FROM pg_stat_archiver;")
    if [ -n "$last" ] && [[ ! "$last" < "$2" ]]; then
      return 0
    fi
    sleep 1
  done
  sql "$1" -c "SELECT * FROM pg_stat_archiver;" >&2
  c logs --tail 40 "$1" >&2
  die "segment $2 was not archived within 60s"
}
objects() {  # objects <prefix>: "<bytes> <path>" for every object under it
  c exec "$PREFIX-objstore" find "/data/$BUCKET/$1" -type f -exec stat -c '%s %n' {} +
}
# Appended to a job container's command: its cgroup's high-water mark, where the runtime exposes it.
# shellcheck disable=SC2016  # expanded inside the container, not here
REPORT_PEAK='echo "peak_memory=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)"'
peak_memory() {
  local bytes
  bytes=$(sed -n 's/^peak_memory=//p' <<<"$1")
  if [ -n "$bytes" ] && [ "$bytes" -gt 0 ]; then
    echo "$((bytes / 1048576)) MiB"
  else
    echo "not reported by this runtime"
  fi
}

remove_all
trap cleanup EXIT
mkdir -p "$WORK"

# ------------------------------------------------------------------ setup
log "starting an S3 object store and copying rclone out of its image, as the init container does"
access_key="drill$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
secret_key="$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')"
# Throwaway values for a store that lives for one run. World-readable because the Postgres uid
# maps outside the host user's range under rootless podman, as the Compose secrets explain.
printf '[default]\naws_access_key_id = %s\naws_secret_access_key = %s\n' "$access_key" "$secret_key" \
  > "$WORK/credentials"
head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$WORK/postgres-password"
chmod 644 "$WORK/credentials" "$WORK/postgres-password"

# Production's backup.env with the endpoint, region and bucket swapped, and nothing else.
sed -e "s|^BACKUP_REMOTE=.*|BACKUP_REMOTE=archive:$BUCKET|" \
    -e "s|^RCLONE_CONFIG_ARCHIVE_ENDPOINT=.*|RCLONE_CONFIG_ARCHIVE_ENDPOINT=http://$PREFIX-objstore:9000|" \
    -e "s|^RCLONE_CONFIG_ARCHIVE_REGION=.*|RCLONE_CONFIG_ARCHIVE_REGION=us-east-1|" \
    "$CONFIG/backup.env" > "$WORK/backup.env"
for key in BACKUP_REMOTE=archive:$BUCKET RCLONE_CONFIG_ARCHIVE_ENDPOINT=http:// RCLONE_CONFIG_ARCHIVE_REGION=us-east-1; do
  grep -q "^$key" "$WORK/backup.env" || die "backup.env no longer has the line the drill rewrites: $key"
done

c network create "$PREFIX" >/dev/null
c run -d --name "$PREFIX-objstore" --network "$PREFIX" --entrypoint sh "$RCLONE_IMAGE" \
  -c "mkdir -p /data/$BUCKET && exec rclone serve s3 --addr :9000 --auth-key $access_key,$secret_key /data" \
  >/dev/null
c volume create "$PREFIX-tools" >/dev/null
c run --rm --user 0 -v "$PREFIX-tools:/tools" --entrypoint cp "$RCLONE_IMAGE" /usr/local/bin/rclone /tools/rclone

common=(
  --network "$PREFIX" --user "$RUN_AS" --env-file "$WORK/backup.env"
  -v "$PREFIX-tools:/tools:ro"
  -v "$CONFIG:/etc/postgresql:ro,z"
  -v "$WORK/credentials:/run/object-storage/credentials:ro,z"
  -v "$WORK/postgres-password:/run/secrets/postgres-password:ro,z"
)

log "starting the primary with production's postgresql.conf and pg_hba.conf"
new_volume "$PREFIX-data"
start_server "$PREFIX-primary" "$PREFIX-data"
wait_for_server "$PREFIX-primary"
cat "$MIGRATIONS"/V*.sql | sql "$PREFIX-primary"
ok "schema applied from the Flyway migrations"

# --------------------------------------------------------------- the backups
insert_batch "$PREFIX-primary" base "$ROWS"
ok "$ROWS rows committed before the base backup"

log "taking a base backup, as the nightly CronJob does"
# Read-only, like the CronJob's container, and reporting the peak memory its own cgroup saw:
# the number the CronJob's memory limit is sized from.
started=$(now)
output=$(c run --rm "${common[@]}" --read-only \
  -e PGHOST="$PREFIX-primary" -e PGUSER=blueprint \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password \
  --tmpfs /backup:rw,mode=1777 \
  "$POSTGRES_IMAGE" sh -c "/etc/postgresql/base-backup.sh && $REPORT_PEAK" 2>&1) \
  || { printf '%s\n' "$output" >&2; die "the base backup failed"; }
grep -v '^peak_memory=' <<<"$output"
backup_seconds=$(since "$started")
backup_peak=$(peak_memory "$output")
ok "base backup uploaded in ${backup_seconds}s"

after_backup=$((ROWS / 2))
insert_batch "$PREFIX-primary" after-backup "$after_backup"
ok "$after_backup rows committed after the backup; only the WAL archive has these"

# The target is truncated to the second, so it must fall a clear second after the last commit
# that has to survive and a clear second before the first that must not.
sleep 1.5
target=$(sql "$PREFIX-primary" -c "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"');")
sleep 1.5
insert_batch "$PREFIX-primary" after-target "$ROWS"
ok "restore target is $target; $ROWS more rows committed after it, which must not come back"

# archive_timeout would ship the open segment within five minutes; the drill does not wait.
segment=$(sql "$PREFIX-primary" -c "SELECT pg_walfile_name(pg_switch_wal());")
wait_archived "$PREFIX-primary" "$segment"
# A nearly empty segment, as archive_timeout produces on a quiet night: what it costs to store.
sql "$PREFIX-primary" -c "CREATE TABLE drill_marker (); DROP TABLE drill_marker;"
segment=$(sql "$PREFIX-primary" -c "SELECT pg_walfile_name(pg_switch_wal());")
wait_archived "$PREFIX-primary" "$segment"
database_size=$(sql "$PREFIX-primary" -c "SELECT pg_size_pretty(pg_database_size('blueprint'));")
ok "WAL archived through $segment"

# ------------------------------------------------------------------ disaster
log "destroying the primary and its volume"
c rm -f "$PREFIX-primary" >/dev/null
c volume rm "$PREFIX-data" >/dev/null
ok "the database is gone; only object storage is left"

# ------------------------------------------------------------------ restore
log "restoring to $target"
started=$(now)
new_volume "$PREFIX-restored-data"
output=$(c run --rm "${common[@]}" --read-only \
  -e PGDATA="$PGDATA" -e RECOVERY_TARGET_TIME="$target" \
  -v "$PREFIX-restored-data:/var/lib/postgresql/data" \
  "$POSTGRES_IMAGE" sh -c "/etc/postgresql/prepare-restore.sh && $REPORT_PEAK" 2>&1) \
  || { printf '%s\n' "$output" >&2; die "preparing the restore failed"; }
grep -v '^peak_memory=' <<<"$output"
prepare_seconds=$(since "$started")
prepare_peak=$(peak_memory "$output")

start_server "$PREFIX-restored" "$PREFIX-restored-data"
promoted=false
for _ in $(seq 1 300); do
  if [ "$(c inspect -f '{{.State.Running}}' "$PREFIX-restored")" != "true" ]; then
    c logs --tail 60 "$PREFIX-restored" >&2
    die "the restored server exited during recovery"
  fi
  if [ "$(sql "$PREFIX-restored" -c 'SELECT pg_is_in_recovery();' 2>/dev/null || true)" = "f" ]; then
    promoted=true
    break
  fi
  sleep 1
done
[ "$promoted" = true ] || { c logs --tail 60 "$PREFIX-restored" >&2; die "recovery did not finish"; }
restore_seconds=$(since "$started")
ok "recovered and promoted in ${restore_seconds}s (${prepare_seconds}s of it fetching and verifying the base backup)"

# ------------------------------------------------------------------- verify
base=$(count_batch "$PREFIX-restored" base)
kept=$(count_batch "$PREFIX-restored" after-backup)
lost=$(count_batch "$PREFIX-restored" after-target)
[ "$base" = "$ROWS" ] || die "expected $ROWS rows from before the backup, found $base"
[ "$kept" = "$after_backup" ] || die "expected $after_backup rows replayed from the WAL archive, found $kept"
[ "$lost" = "0" ] || die "found $lost rows committed after the target; recovery did not stop where asked"
ok "every row committed before the target is back ($base + $kept), none after it"

[ "$(sql "$PREFIX-restored" -c 'SHOW data_checksums;')" = "on" ] || die "data checksums are off"
c exec "$PREFIX-restored" pg_amcheck -U blueprint --install-missing --heapallindexed blueprint
ok "data checksums on; pg_amcheck found every heap and index intact"

timeline=$(sql "$PREFIX-restored" -c "SELECT substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8);")
[ "$timeline" = "00000002" ] || die "expected the restored server on timeline 2, it is on $timeline"
insert_batch "$PREFIX-restored" after-restore 1
for _ in $(seq 1 30); do
  c exec "$PREFIX-objstore" test -f "/data/$BUCKET/wal/00000002.history.gz" && break
  sleep 1
done
c exec "$PREFIX-objstore" test -f "/data/$BUCKET/wal/00000002.history.gz" \
  || die "the restored server is not archiving: no history file for timeline 2"
ok "promoted onto timeline 2, accepting writes, and archiving them"

# ------------------------------------------------------------------ summary
backup_bytes=$(objects backups | awk '{ s += $1 } END { print s }')
wal=$(objects wal | awk '$2 ~ /\/[0-9A-F]{24}\.gz$/')
wal_count=$(awk 'END { print NR }' <<<"$wal")
wal_bytes=$(awk '{ s += $1 } END { print s }' <<<"$wal")
wal_smallest=$(sort -n <<<"$wal" | head -1 | awk '{ print $1 }')

{
  printf 'restore drill, %s\n\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  rows before the backup / after it / after the target   %s / %s / %s\n' "$ROWS" "$after_backup" "$ROWS"
  printf '  database size at the target                            %s\n' "$database_size"
  printf '  base backup: time to take and upload                   %ss\n' "$backup_seconds"
  printf '  base backup: size in object storage                    %s KiB\n' "$((backup_bytes / 1024))"
  printf '  base backup: peak memory of the job container          %s\n' "$backup_peak"
  printf '  WAL archived: segments / total                         %s / %s KiB\n' "$wal_count" "$((wal_bytes / 1024))"
  printf '  WAL archived: smallest segment (16 MiB uncompressed)   %s KiB\n' "$((wal_smallest / 1024))"
  printf '  restore: fetch and verify the base backup              %ss\n' "$prepare_seconds"
  printf '  restore: peak memory of the prepare container          %s\n' "$prepare_peak"
  printf '  restore: until promoted and writable                   %ss\n' "$restore_seconds"
} | tee "$WORK/summary.txt"

printf '\n%srestored to %s: nothing committed before it lost, nothing after it kept%s\n' "$GREEN" "$target" "$RESET"
