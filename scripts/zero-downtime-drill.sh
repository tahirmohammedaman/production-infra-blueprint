#!/usr/bin/env bash
#
# Proves the zero-downtime claim instead of asserting it: constant traffic through a rolling
# restart of the API, in a real Kubernetes cluster, failing if a single request fails.
#
# What it stands up is the dev overlay — the same base Deployment, probes, rollout strategy,
# NetworkPolicies and Pod Security Standard as production — in a local kind cluster at the
# Kubernetes version k3s runs, with the API at production's two replicas. The load comes from
# k6 inside the cluster, in the `traefik` namespace, because that is the only place the API's
# NetworkPolicy admits HTTP from: the requests take the path real traffic takes.
#
#   make drill                           build, deploy, run the drill; leaves the cluster up
#   PRESTOP=sleep make drill             the same with only a pause before SIGTERM, or
#   PRESTOP=none make drill              with no hook at all — to see the drain matter
#   ROLLOUTS=3 WRITE_RATIO=0.5 make drill
#                                        a harsher run: more rollouts, more unretried writes
#   SKIP_BUILD=true make drill           reuse the images already in the cluster
#   make drill-down                      delete the cluster
#
# Needs kind and kubectl on the PATH, and docker or podman. Rootless podman works; kind calls
# its support experimental, and this script opts in to it.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/lib.sh

CLUSTER="${CLUSTER:-blueprint-drill}"
NAMESPACE=blueprint-dev
RATE="${RATE:-40}"
DURATION="${DURATION:-150s}"
WRITE_RATIO="${WRITE_RATIO:-0.2}"
ROLLOUTS="${ROLLOUTS:-1}"
PRESTOP="${PRESTOP:-drain}"
SKIP_BUILD="${SKIP_BUILD:-false}"
TAG=drill
# The node image for the Kubernetes minor k3s runs (infra/ansible/roles/k3s), digest-pinned
# like every other image here.
NODE_IMAGE=kindest/node:v1.33.12@sha256:3f5c8443c620245e4d355cfe09e96a91ead32ceaa569d3f1ca9edf0cb2fe2ff4
K6_IMAGE=docker.io/grafana/k6:2.1.0@sha256:65c920dc067d5e2e00befbf982af6ad6ad0117034e8b1c65817c7975c52d4669
WORK=.rendered/drill

for tool in kind kubectl; do
  command -v "$tool" >/dev/null || die "$tool is required: https://kind.sigs.k8s.io, https://kubernetes.io/docs/tasks/tools/"
done
CONTAINER=$(container_cmd)
if [ "$CONTAINER" = podman ]; then
  export KIND_EXPERIMENTAL_PROVIDER=podman
fi
mkdir -p "$WORK"
export KUBECONFIG="$PWD/$WORK/kubeconfig"
kc() { kubectl -n "$NAMESPACE" "$@"; }

# ------------------------------------------------------------------- cluster
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  ok "reusing cluster $CLUSTER"
else
  log "creating cluster $CLUSTER"
  kind create cluster --name "$CLUSTER" --image "$NODE_IMAGE" --wait 180s
fi
kind get kubeconfig --name "$CLUSTER" > "$KUBECONFIG"

# The StatefulSets ask for `local-path`, k3s' storage class, which on the real node lands on
# the Hetzner volume. kind ships the same provisioner under the name `standard`; this gives it
# the name the manifests expect rather than changing the manifests for a test cluster.
kubectl apply -f - >/dev/null <<'YAML'
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: local-path
provisioner: rancher.io/local-path
volumeBindingMode: WaitForFirstConsumer
reclaimPolicy: Delete
YAML

# -------------------------------------------------------------------- images
if [ "$SKIP_BUILD" = "true" ]; then
  ok "reusing the images already loaded into the node"
else
  log "building the images"
  for service in api worker; do
    "$CONTAINER" build --quiet --target final --build-arg SERVICE="$service" \
      -t "blueprint-$service:$TAG" services >/dev/null
    "$CONTAINER" save -o "$WORK/$service.tar" "blueprint-$service:$TAG" >/dev/null
    kind load image-archive "$WORK/$service.tar" --name "$CLUSTER" >/dev/null
    rm -f "$WORK/$service.tar"
  done
  ok "images loaded into the node"
fi
# podman names local images localhost/<name>; docker leaves them bare.
REGISTRY_PREFIX=""
[ "$CONTAINER" = podman ] && REGISTRY_PREFIX="localhost/"

# ------------------------------------------------------------------- deploy
# The preStop hook under test. `drain` is what the manifests ship; the other two exist to show
# what it is for, and failures are the expected outcome of both under enough writes.
case "$PRESTOP" in
  drain)
    PRESTOP_PATCH="" ;;
  sleep)
    warn "running with a plain 5s pause instead of the drain"
    PRESTOP_PATCH='
patches:
  - patch: |
      - op: replace
        path: /spec/template/spec/containers/0/lifecycle/preStop
        value:
          sleep:
            seconds: 5
    target:
      kind: Deployment
      name: api' ;;
  none)
    warn "running with no preStop hook at all"
    PRESTOP_PATCH='
patches:
  - patch: |
      - op: remove
        path: /spec/template/spec/containers/0/lifecycle
    target:
      kind: Deployment
      name: api' ;;
  *)
    die "PRESTOP must be drain, sleep or none (got '$PRESTOP')" ;;
esac

cat > "$WORK/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../deploy/k8s/overlays/dev
images:
  - name: ghcr.io/tahirmohammedaman/blueprint-api
    newName: ${REGISTRY_PREFIX}blueprint-api
    newTag: $TAG
  - name: ghcr.io/tahirmohammedaman/blueprint-worker
    newName: ${REGISTRY_PREFIX}blueprint-worker
    newTag: $TAG
# Production's replica count, so a rollout replaces pods while others serve.
replicas:
  - name: api
    count: 2
$PRESTOP_PATCH
YAML

log "deploying the dev overlay"
# A Job's pod template is immutable, so re-applying one with a new image fails with "field is
# immutable" — on every run after the first. Flux handles it with `force: true` on the stages
# that own Jobs; here they are deleted first. Both are idempotent, so running them again is the
# intended outcome, not a side effect.
kc delete job migrate kafka-topics --ignore-not-found --wait=true >/dev/null 2>&1 || true
kubectl apply -k "$WORK" >/dev/null
for store in postgres redis kafka; do
  kc rollout status "statefulset/$store" --timeout=300s >/dev/null
done
kc wait --for=condition=complete job/kafka-topics --timeout=300s >/dev/null
kc wait --for=condition=complete job/migrate --timeout=600s >/dev/null
kc rollout status deployment/api --timeout=300s >/dev/null
kc rollout status deployment/worker --timeout=300s >/dev/null
ok "stack is up"

# ----------------------------------------------------------------------- load
kubectl create namespace traefik --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n traefik delete job k6-rollout --ignore-not-found --wait=true >/dev/null
kubectl -n traefik create configmap k6-rollout --from-file=load/k6/rollout.js \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl apply -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-rollout
  namespace: traefik
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: k6
          image: $K6_IMAGE
          args: [run, --quiet, /scripts/rollout.js]
          env:
            - name: API_URL
              value: http://api.$NAMESPACE.svc:8080
            - name: RATE
              value: "$RATE"
            - name: DURATION
              value: "$DURATION"
            - name: WRITE_RATIO
              value: "$WRITE_RATIO"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: scripts
              mountPath: /scripts
      volumes:
        - name: scripts
          configMap:
            name: k6-rollout
YAML

log "load running at $RATE requests/s for $DURATION"
kubectl -n traefik wait --for=condition=ready pod -l job-name=k6-rollout --timeout=180s >/dev/null
# Let the load settle so the rollout starts against steady traffic, not a cold start.
sleep 20

rm -f "$WORK"/replaced-*.log
for round in $(seq 1 "$ROLLOUTS"); do
  log "rollout $round of $ROLLOUTS: replacing every API pod while the load runs"
  # The pods being replaced take their logs with them, and their last seconds are exactly what
  # a failed drill needs explained. Follow each one into a file until it is gone.
  followers=()
  for pod in $(kc get pods -l app.kubernetes.io/name=api -o name); do
    kc logs -f "$pod" > "$WORK/replaced-${pod#pod/}.log" 2>&1 &
    followers+=("$!")
  done
  kc rollout restart deployment/api >/dev/null
  kc rollout status deployment/api --timeout=300s >/dev/null
  sleep 45   # the old pods' drain window, so their shutdown is in the files
  kill "${followers[@]}" 2>/dev/null || true
done
ok "rollouts complete; logs of the replaced pods are in $WORK/replaced-*.log"

log "waiting for the load to finish"
while :; do
  succeeded=$(kubectl -n traefik get job k6-rollout -o jsonpath='{.status.succeeded}')
  failed=$(kubectl -n traefik get job k6-rollout -o jsonpath='{.status.failed}')
  [ -n "$succeeded$failed" ] && break
  sleep 5
done

kubectl -n traefik logs job/k6-rollout > "$WORK/k6.log"
# Failed requests grouped by what failed them: a status means the API answered, status 0 with
# an error means the connection did. They point at different fixes.
failures=$(grep ' failed: ' "$WORK/k6.log" \
  | sed -n 's/.*msg="\(.*\)" source=console.*/\1/p' | sed 's/\\"/"/g' | sort | uniq -c | sort -rn || true)
if [ -n "$failures" ]; then
  printf '\nfailed requests, by reason:\n%s\n' "$failures"
fi
sed -n '/THRESHOLDS/,$p' "$WORK/k6.log"

if [ "${succeeded:-0}" = "1" ]; then
  printf '\n%szero failed requests across a rolling restart of every API pod%s\n' "$GREEN" "$RESET"
else
  die "requests failed during the rollout; the k6 summary above says how many"
fi
