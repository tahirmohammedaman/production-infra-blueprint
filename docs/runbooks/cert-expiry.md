# Runbook: a certificate is not renewing, or has expired

**Symptom:** [CertificateExpiringSoon](alerts.md#certificateexpiringsoon) raised a ticket, or
clients report a TLS error.

**Why this one needs its own runbook:** an expired certificate is a total outage the
error-budget alerts cannot see. The TLS handshake fails before Traefik routes the request, so no
router metric counts it and the availability objective reads 100% while every request fails. The
ticket fires fourteen days out for exactly that reason. Do not let it age.

**How renewal normally works:** cert-manager renews 30 days before expiry. It creates an Order
with Let's Encrypt, which sends an HTTP-01 challenge to
`http://blueprint.tahir.dev/.well-known/acme-challenge/...`; cert-manager answers it through a
temporary Ingress on the `traefik` class, and the new certificate replaces the old one in the
`blueprint-tls` Secret. Traefik picks it up without a restart.

---

## 1. Where did it stop?

Each resource is one step further along; the first one that is not Ready is the problem.

```bash
kubectl -n blueprint get certificate blueprint-tls
kubectl -n blueprint get certificaterequests,orders,challenges
kubectl -n blueprint describe challenges                    # the reason is in the events
kubectl -n cert-manager logs deploy/cert-manager --tail=100
```

## 2. The usual causes

- **Port 80 is not reachable from the internet.** HTTP-01 needs it, even though users only use
  443. Check the firewall (`infra/terraform/modules/firewall`) still has the port 80 rule, and
  that it answers from outside:

  ```bash
  curl -sI http://blueprint.tahir.dev/.well-known/acme-challenge/probe   # a 404 from Traefik is fine
  ```

- **DNS points somewhere else, or through a proxy.** The Cloudflare records are unproxied on
  purpose: a proxied record answers the challenge at Cloudflare's edge and the challenge fails
  with a 404 or a certificate for the wrong name. `dig +short blueprint.tahir.dev` must return the
  node's address (`terraform output node_ipv4`).
- **Rate limited.** Let's Encrypt allows five duplicate certificates a week. The Order's events
  say `rateLimited`, and the only fix is waiting. This is why every experiment happens against the
  staging issuer, below.
- **cert-manager is not running,** or its webhook is failing: `flux get helmreleases -A`.

## 3. Renew it

Once the cause is fixed, ask for a renewal rather than waiting for the next retry:

```bash
cmctl renew -n blueprint blueprint-tls
kubectl -n blueprint get certificate blueprint-tls -w
```

Do not delete the `blueprint-tls` Secret to force it. Until a new certificate issues, Traefik
serves its default self-signed one, which turns "expires in ten days" into "broken now".

## 4. If it has already expired

The outage is already happening, so the priority is a valid certificate, not a clean one.

1. Fix the cause from step 2 and renew. Most of the time that is enough.
2. If renewals keep failing and it is not obvious why, debug against staging so failed attempts
   do not use up the production rate limit. Suspend Flux first, or it reverts the change within
   minutes:

   ```bash
   flux suspend kustomization apps
   kubectl -n blueprint annotate ingress api --overwrite \
     cert-manager.io/cluster-issuer=letsencrypt-staging
   ```

   A staging certificate is not trusted by browsers, but it proves the challenge path works. Put
   the production issuer back the same way, then `flux resume kustomization apps`, so git and the
   cluster agree again.

## 5. Check

```bash
echo | openssl s_client -connect blueprint.tahir.dev:443 -servername blueprint.tahir.dev 2>/dev/null \
  | openssl x509 -noout -issuer -dates
```

The issuer should be Let's Encrypt's production CA and `notAfter` about ninety days out. The
alert clears within an hour of the new certificate appearing in the metric.
