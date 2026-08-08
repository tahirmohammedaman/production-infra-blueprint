# Runbook: restore the database

**Symptom:** the data is wrong or gone. A migration or a bug destroyed rows, someone ran a
`DELETE` without a `WHERE`, or the volume itself is lost.

**What you get back:** the database as it was at any second from the oldest base backup
(14 days ago) to about five minutes ago. The five minutes is `archive_timeout`: WAL written since
the last archived segment exists only on the node.

**What you lose:** everything committed after the moment you choose. That is the point of a
point-in-time restore — choose the moment just before the damage — and it is also why section 0
exists.

**How long:** minutes. Writes are stopped for the duration. See [the numbers](#the-numbers) for
what the restore drill measured.

---

## 0. Is this a restore?

A restore is the only procedure in these runbooks that deliberately throws data away. Rule the
others out first.

- A deploy went bad and the data is fine → [rollback.md](rollback.md).
- A dependency is down → [incident-triage.md](incident-triage.md). An unreachable database is not
  a lost one.
- A migration failed → the application never rolled; see step 4 of [rollback.md](rollback.md).

Then choose the target: **the last moment the data was right**, in UTC, in exactly this form:
`2026-08-06T21:14:00Z`. The damaging request's timestamp is in Loki; a migration's is in
`flyway_schema_history.installed_on`.

When in doubt, choose earlier. The data directory being replaced is moved aside, not deleted, so
a legitimate write that falls after the target can still be copied out of it afterwards. A
damaging write that falls before the target is the damage itself, restored.

Say in the incident channel that writes are about to stop.

## 1. Stop everything that writes, or would undo the next steps

Flux first: it would otherwise scale Postgres straight back up in the middle of the restore.

```bash
flux suspend kustomization apps apps-data
kubectl -n blueprint scale deployment api worker --replicas=0
kubectl -n blueprint patch cronjob postgres-backup -p '{"spec":{"suspend":true}}'
kubectl -n blueprint scale statefulset postgres --replicas=0
kubectl -n blueprint wait --for=delete pod/postgres-0 --timeout=120s
```

The gateway now answers 503 to everything, which is honest: the service is down.

## 2. Say where to stop

```bash
kubectl -n blueprint create configmap postgres-restore \
  --from-literal=RECOVERY_TARGET_TIME=2026-08-06T21:14:00Z
```

The restore uses the newest base backup that finished before the target. To use a specific one,
add `--from-literal=BACKUP=20260806T023000Z`.

If the volume was lost and the data was fine, skip this step: without the ConfigMap the restore
replays everything that was archived.

## 3. Lay down the base backup

```bash
kubectl apply -k deploy/k8s/restore
kubectl -n blueprint wait --for=condition=complete job/postgres-restore --timeout=30m
kubectl -n blueprint logs job/postgres-restore -c restore
```

The log names the base backup it chose, where it moved the old data directory, and confirms the
backup matched its manifest:

```
prepare-restore: restoring from base backup 20260806T023012Z, target 2026-08-06T21:14:00Z
prepare-restore: moved the existing data directory aside to /var/lib/postgresql/data/pgdata.pre-restore-20260806T213502Z
prepare-restore: base backup verified against its manifest
prepare-restore: ready: start the server and it will recover, then promote
```

- **`postmaster.pid exists`** — the server is still running: step 1 has not finished. If it
  crashed and is certainly gone, add `--from-literal=FORCE=true` to the ConfigMap, delete the
  Job, and apply it again.
- **`no complete base backup finished before`** — the target is older than every backup still in
  the bucket, or the backups stopped (see
  [PostgresBackupMissing](alerts.md#postgresbackupmissing)).
- **A `pg_verifybackup` error** — that backup is damaged. Pin the one before it with `BACKUP=`;
  WAL replay covers the extra day.

## 4. Start the server and let it recover

```bash
kubectl -n blueprint scale statefulset postgres --replicas=1
kubectl -n blueprint logs -f postgres-0 -c postgres
```

Postgres fetches WAL from the archive, stops at the target and promotes itself onto a new
timeline. The lines that matter:

```
starting point-in-time recovery to 2026-08-06 21:14:00+00
recovery stopping before commit of transaction 51873, time 2026-08-06 21:14:02.3+00
selected new timeline ID: 2
archive recovery complete
database system is ready to accept connections
```

Ready is not the same as recovered: the server accepts read-only connections while it replays.
Wait for this to say `f`:

```bash
kubectl -n blueprint exec postgres-0 -c postgres -- \
  psql -U blueprint -d blueprint -tAc 'SELECT pg_is_in_recovery()'
```

Then remove the target, so nothing mistakes it for configuration later:

```bash
kubectl -n blueprint exec postgres-0 -c postgres -- psql -U blueprint -d blueprint \
  -c 'ALTER SYSTEM RESET recovery_target_time' -c 'ALTER SYSTEM RESET recovery_target_action'
```

## 5. Check it before anyone else does

```bash
kubectl -n blueprint exec postgres-0 -c postgres -- psql -U blueprint -d blueprint -c "
  SELECT count(*) AS items, max(updated_at) AS last_write FROM items;" -c "
  SELECT max(version) AS schema FROM flyway_schema_history WHERE success;"
```

`last_write` should be just before the target. If the schema is older than the release that is
about to start — the target predates a migration — run the migration first, or the pods will
start against a schema they do not expect:

```bash
flux resume kustomization apps-data
flux reconcile kustomization apps-migrations --with-source
```

## 6. Resume

```bash
kubectl -n blueprint delete configmap postgres-restore
kubectl -n blueprint delete job postgres-restore
flux resume kustomization apps-data apps
kubectl -n blueprint rollout status deployment/api --timeout=180s
```

Flux puts the replica counts back. It does not un-suspend the CronJob, because the manifest never
mentions `suspend` and Flux only manages the fields it applies. Do it by hand, and take a base
backup on the new timeline now rather than at 02:30, so the next restore does not start from the
old one:

```bash
kubectl -n blueprint patch cronjob postgres-backup -p '{"spec":{"suspend":false}}'
kubectl -n blueprint create job --from=cronjob/postgres-backup postgres-backup-after-restore
```

## 7. What the restore did to everything else

The database went back in time. Nothing else did.

- **The inventory read model** in Redis still counts the writes after the target. The reconciler
  rewrites it on its next quiet run and [ProjectionDriftCorrected](alerts.md#projectiondriftcorrected)
  fires: expected, and the ticket can be closed with a link to this incident.
- **Events already published for writes after the target** stay in Kafka and were applied by the
  worker. Their rows no longer exist; the reconciler's correction is what undoes their effect.
- **Outbox events that were unpublished at the target** are unpublished again, and the relay
  publishes them. Some of them the worker has already applied. It is idempotent on event id, so
  the second delivery is a no-op — the property that makes this step safe rather than a second
  incident.
- **Caches** of individual items expire on their TTL. A deleted-then-restored item may read stale
  for up to that long.

## Afterwards

- The replaced data directory is still on the volume, at `pgdata.pre-restore-<timestamp>`, and
  doubles its usage. It holds everything up to the incident, including legitimate writes after
  the target. Copy out what is needed, then delete it:

  ```bash
  kubectl -n blueprint exec postgres-0 -c postgres -- du -sh /var/lib/postgresql/data/pgdata.pre-restore-*
  kubectl -n blueprint exec postgres-0 -c postgres -- rm -rf /var/lib/postgresql/data/pgdata.pre-restore-<timestamp>
  ```

- Record the target, the base backup used, how long each step took and what was lost.
- If the restore was needed because of a migration, the fix is a check that would have caught it
  — see [zero-downtime-migration.md](zero-downtime-migration.md) — not a more careful reviewer.

## The numbers

`make restore-drill` runs steps 3 to 5 against containers, with production's configuration and
scripts and an S3 server standing in for the bucket. CI runs it weekly with 100,000 rows.

| Measurement | 30,000 rows (27 MB) | 300,000 rows (205 MB) |
| --- | ---: | ---: |
| Base backup, taken and uploaded | 2.0 s | 4.0 s |
| Base backup in object storage | 5.3 MB | 15.2 MB |
| WAL archived by the drill | 11 MB | 80 MB |
| Fetch and verify the base backup | 1.6 s | 2.6 s |
| Until promoted and writable | 5.6 s | 10.6 s |
| A timed-out WAL segment in object storage | 16 KB of 16 MB | 16 KB of 16 MB |

Restore time grows with the WAL to replay far more than with the base backup: the base backup
took one second more at seven times the size, the whole restore five. That is the argument for a
daily base backup rather than a weekly one.

The procedure itself — steps 1 to 5, command by command, minus the Flux lines — was run against
these manifests in a kind cluster, with the StatefulSet archiving, the CronJob backing up and the
Job restoring under the `restricted` Pod Security Standard and the production NetworkPolicies.
Steps 1 to 4 took 12 seconds for a 27 MB database; recovery stopped at the first commit after the
target and promoted onto timeline 2.
