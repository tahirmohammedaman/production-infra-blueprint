# Runbook: change the schema without downtime

**Use when:** writing a Flyway migration. Read it before the migration is written, not after it
has failed.

## Why this is the hard part of a zero-downtime deploy

The migration Job runs first, in its own Flux stage (`apps-migrations`), and the application
rolls only after it succeeds. For the length of the rollout — and for as long as a rollback keeps
the previous release running — **the old code runs against the new schema.**

So every migration has one rule: **the release before it must keep working after it is applied.**
In practice that means migrations only add. Anything that removes or changes what the previous
release reads or writes is split into steps, shipped across releases: expand first, contract
later. The zero-downtime drill proves the rollout drops no requests; this rule is what makes the
same true of a schema change.

## What is safe, and what to do instead

| Change | In one release? | Instead |
| --- | --- | --- |
| Add a table | yes | |
| Add a nullable column, or one with a constant default | yes | |
| Add an index | yes, but not in the same transaction as other changes | `CREATE INDEX CONCURRENTLY` in a migration of its own, with `executeInTransaction=false` in a `V<n>__<name>.sql.conf` beside it |
| Add a `NOT NULL` column without a default | **no** — old pods insert rows without it | add it nullable; write it from the code; backfill; then add the constraint (below) |
| Add `NOT NULL` or a foreign key to an existing column | **no** — validating it locks the table | add the constraint `NOT VALID`, then `VALIDATE CONSTRAINT` in a later migration |
| Rename a column or table | **no** — old pods query the old name | four releases: add the new column; write both; backfill and read the new one; drop the old |
| Change a column's type | **no** | a new column, as for a rename |
| Drop a column or table | **no** — old pods still map it | stop using it in release N; drop it in N+1 |

Hibernate is part of the old code. An entity that still maps a column fails on every query once
the column is gone, even if nothing reads the field, so the drop always waits a release.

## Worked example: renaming `items.description` to `details`

| Release | Migration | Code |
| --- | --- | --- |
| N | `V3__add_items_details.sql`: `ALTER TABLE items ADD COLUMN details VARCHAR(2000);` | writes both columns, reads `description` |
| N+1 | `V4__backfill_items_details.sql`: `UPDATE items SET details = description WHERE details IS NULL;` | writes both, reads `details` |
| N+2 | none | writes and reads `details` only |
| N+3 | `V5__drop_items_description.sql`: `ALTER TABLE items DROP COLUMN description;` | unchanged |

A rollback from any release lands on code that works with the schema it finds. That is the whole
point: the code can always go back, and the schema never has to.

## The guard

`.semgrep/blueprint.yml` fails CI on a migration that drops or renames a column or table, changes a
column's type, or adds `NOT NULL` without a default. When a contract step is genuinely safe —
release N+3 above — mark that statement, and say why, on the line before it:

```sql
-- Unused since V3 shipped in release N; nothing has read it for two releases.
-- nosemgrep: blueprint-migration-must-be-expand-only
ALTER TABLE items DROP COLUMN description;
```

The marker is the review: whoever approves the change is agreeing with the reason next to it.

## Write it so it fails fast

Put this first in any migration that touches a busy table:

```sql
SET LOCAL lock_timeout = '5s';
```

An `ALTER TABLE` waits for every transaction already holding the table, and while it waits, every
new query on the table queues behind it. Without a timeout, one slow transaction turns a
millisecond migration into a write outage. With it, the migration fails, the Job fails, the
rollout never starts, and the previous release keeps serving — the failure mode this pipeline is
built to have.

## When a migration fails

The application did not roll, so nothing is broken yet. Fix forward with a new migration; never
edit `flyway_schema_history` by hand. See step 4 of [rollback.md](rollback.md).

## How it is tested

Every build runs every migration against a real Postgres in the integration suite, and
`make restore-drill` applies them to the database it backs up and restores. A migration that does
not apply on Postgres 16 fails the build, not the deploy.
