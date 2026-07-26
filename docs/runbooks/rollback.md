# Runbook: roll back a bad deploy

**Symptom:** a release is live and wrong. Errors are up, latency is up, or a feature is
behaving badly enough that waiting for a fix is worse than going back.

**Time to safe state:** about two minutes for the fast path.

---

## 0. Before anything: is this a rollback?

A rollback undoes *code*. It does not undo a migration, and it does not fix a dependency
outage. Two minutes of checking saves an hour of confusion.

```bash
kubectl -n blueprint get pods
kubectl -n blueprint logs deploy/api --tail=100 | jq -r 'select(.level=="ERROR")'
kubectl -n flux-system get kustomizations
```

- Pods `CrashLoopBackOff` right after a deploy → rollback.
- Pods healthy, errors from Postgres or Kafka → not a rollback. See `incident-triage.md`.
- The `migrate` Job failed → **stop**. The application never rolled; see step 4.

---

## 1. Stop Flux from re-applying what you are about to undo

Image automation will otherwise notice the newest tag again and put it straight back.

```bash
flux suspend image update blueprint
flux suspend kustomization apps
```

This is the step people skip, and it is why "the rollback didn't hold".

---

## 2. Fast path: roll back the Deployment in place

Buys time. It does **not** change git, so the cluster now disagrees with the repository —
which is exactly why step 1 came first, and why step 3 is not optional.

```bash
kubectl -n blueprint rollout undo deployment/api
kubectl -n blueprint rollout status deployment/api --timeout=120s
```

`revisionHistoryLimit: 3` means the last three are available. Confirm it recovered before
moving on:

```bash
curl -sf https://blueprint.tahir.dev/api/v1/items >/dev/null && echo ok
```

---

## 3. Real path: put the repository back to the truth

The cluster must end up matching git. Either revert the commit:

```bash
git revert --no-edit <bad-sha>
git push
```

or pin the image tag by hand in `deploy/k8s/overlays/prod/kustomization.yaml` to the last
known-good `<YYYYMMDDHHmmss>-<sha>` tag, and commit that.

Then resume and let Flux reconcile:

```bash
flux resume kustomization apps
flux reconcile kustomization apps --with-source
kubectl -n blueprint rollout status deployment/api --timeout=180s
```

Resume image automation only once the offending image is no longer the newest by tag
ordering, or it will immediately re-deploy it:

```bash
flux resume image update blueprint
```

---

## 4. If the migration is what failed

The `apps` Kustomization depends on `apps-migrations`, so a failed Job means the application
never rolled at all — the old version is still serving, and there is no user-facing outage
to rush.

```bash
kubectl -n blueprint logs job/migrate
kubectl -n flux-system describe kustomization apps-migrations
```

Migrations in this repository are expand/contract, so the previous release runs against the
new schema by construction: an expand step only adds. Fix forward with a new migration.

**Do not** hand-edit `flyway_schema_history` to make the Job pass. It is the only record of
what has actually been applied, and a repaired-by-hand row makes the next restore silently
produce a schema nobody has.

If the expand step itself is wrong, write a compensating migration. Rolling a schema
backwards while an application is running against it is how data gets lost.

---

## 5. Afterwards

- Confirm `kubectl -n flux-system get kustomizations` shows every one Ready and not
  suspended. A Kustomization left suspended is a deploy that silently stops happening.
- Note the bad tag in the incident record, so image automation's ordering is understood
  next time.
- If the rollback was needed because CI passed on something broken, the fix is a test, not
  a stricter review.
