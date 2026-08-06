# 9. Point-in-time recovery from archived WAL, with no operator and no derived image

Date: 2026-08-06
Status: Accepted

## Context

Postgres is the only store here that holds something which cannot be rebuilt. Redis is a
projection the reconciler recomputes, and Kafka's events are already applied; the items table is
the system of record.

What a backup has to survive is mostly not hardware. The volume is a replicated Hetzner block
device. The likely losses are a migration that drops the wrong thing, a bug that overwrites rows,
and a `DELETE` typed into the wrong terminal — each of which a nightly copy would restore to
*last night*, taking a day of legitimate writes with it. The requirement is therefore a restore
to a chosen moment, just before the damage, with a recovery point measured in minutes.

The bucket already existed with 14-day lifecycle rules for `wal/` and `backups/`
(`infra/terraform/modules/storage`). The constraints were the usual ones for this repository: a
single node, a measured footprint, nothing paid, and a restore that is tested rather than trusted.

Four ways to get there were considered.

| Option | Recovery point | What it costs |
| --- | --- | --- |
| Nightly `pg_dump` to the bucket | 24 hours | Nothing to run. No point-in-time restore at all, and a restore replays SQL, which is slow at any real size. |
| WAL-G or pgBackRest | minutes | Both run inside the Postgres container, since that is where `archive_command` executes. A derived Postgres image to build, scan, sign and rebuild for every Postgres patch release. |
| CloudNativePG operator | minutes | Replaces the StatefulSet with an operator and its CRDs: a second control loop owning the database, and a second answer to "how does Postgres run here". |
| Postgres' own archiving, with rclone copied in | minutes | Four short scripts to own. |

## Decision

Postgres' built-in continuous archiving: `archive_command` ships every completed WAL segment to
the bucket, a nightly CronJob takes a `pg_basebackup`, and a restore lays down a base backup and
lets the server replay archived WAL to a target time with `restore_command`.

The only thing the stock image lacks is an S3 client. rclone is a single static binary, so an
init container copies it from its own digest-pinned image into a shared volume and the Postgres
container runs it from there. No image is derived from `postgres`, so there is nothing new to
build, scan or sign, and Renovate moves both images independently.

All of it is four scripts and two configuration files in `deploy/k8s/base/postgres/config/`:
`archive-wal.sh`, `restore-wal.sh`, `base-backup.sh`, `prepare-restore.sh`, `postgresql.conf`
and `pg_hba.conf`. `scripts/restore-drill.sh` mounts the same files into the same images and
proves, in CI every week, that a backup restores to the second.

## Consequences

**Recovery point: five minutes or better.** A segment is archived when it fills or when
`archive_timeout` (300 s) expires, whichever comes first. A timed-out segment is shipped whole
— 16 MB — but with `wal_recycle = off` its unused tail is zeros and gzip takes it to 16 KB. That
was measured in the drill, and it is what makes a five-minute timeout cheap enough to store for 14
days.

**Recovery time is measured, not estimated.** The drill restores 30,000 rows to a point in time
in under six seconds, including verifying the base backup against its manifest. At real sizes the
time is dominated by downloading the base backup and replaying up to a day of WAL, which is why a
base backup is taken daily rather than weekly.

**A failed archive loses nothing, and says so.** Postgres keeps every segment until
`archive_command` succeeds for it and retries in order. The cost of a long failure is a growing
`pg_wal`, so `PostgresWalArchivingFailing` raises a ticket after twenty minutes of failures with
nothing shipped. `PostgresBackupMissing` covers the CronJob, from kube-state-metrics restricted to
exactly that CronJob's two series. `postgres_exporter` runs as its own Deployment rather than a
sidecar, because a crashlooping sidecar makes the database pod unready and removes it from DNS.

**The database will not start without its archive credential.** The `postgres-object-storage`
Secret is created by hand, like Loki's, and the StatefulSet does not mark it optional. A
production database that cannot archive fails where someone is watching, on first deploy, instead
of running for weeks with no way back.

**Restore is deliberate.** There is no automatic failover to a restored copy. A restore throws away
everything after its target, so it is a decision a person makes, following
`docs/runbooks/db-restore.md`. The Job that prepares the volume is not reconciled by Flux, and it
refuses to touch a data directory whose server is still running.

**Not done, deliberately:**

- *Client-side encryption.* The bucket is private and encrypted at rest by the provider, but
  anyone holding the bucket key can read the backups. Wrapping the remote in an rclone `crypt`
  remote is a configuration change, with a key to store somewhere that survives losing the
  cluster. That trade-off is recorded in `docs/security.md` rather than made silently.
- *A standby.* Backups protect the data, not availability. A second Postgres needs a second node
  to mean anything, and that is an architecture change with a price in `docs/cost-analysis.md`.
- *Incremental backups and parallel restore* — pgBackRest's reasons to exist. At this size a full
  base backup takes seconds.

**When to revisit.** A second Postgres instance or a standby: move to CloudNativePG, which does
all of this declaratively and failover as well. A database large enough that a daily full base
backup is expensive to take or to download: pgBackRest, and accept the derived image.
