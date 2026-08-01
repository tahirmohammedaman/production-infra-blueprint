// Zero-downtime drill: constant traffic across a rolling restart of the API.
//
// Run by scripts/zero-downtime-drill.sh from inside the cluster while `kubectl rollout restart`
// replaces every API pod. One threshold gates the run: not a single request may fail. Latency is
// reported and not gated — a deploy that is slow for a few seconds is a different problem from
// one that drops requests, and folding the two together hides the one that matters here.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.API_URL || 'http://localhost:8080';
// Share of requests that are writes. Writes are the ones a client will not retry on its own.
const WRITE_RATIO = Number(__ENV.WRITE_RATIO || 0.2);

export const options = {
  scenarios: {
    rollout: {
      // Arrival rate, not a fixed number of virtual users: requests keep arriving at the same
      // pace while pods are replaced, the way real clients do, instead of politely waiting for a
      // slow response before sending the next one.
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 40),
      timeUnit: '1s',
      duration: __ENV.DURATION || '120s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// A failed request is printed with what failed it: a status code means the API answered, an
// error code with status 0 means the connection did. The two point at different fixes, and a
// summary that only counts failures cannot tell them apart.
function report(op, res, ok) {
  if (!ok) {
    console.error(`${op} failed: status=${res.status} error_code=${res.error_code} error="${res.error}"`);
  }
}

export default function () {
  // Writes by default one request in five, so the rollout is tested against the path that holds
  // a database transaction open and that no HTTP client retries by itself — not only against
  // cheap reads, which Go's client quietly retries on a dead keep-alive connection.
  if (Math.random() < WRITE_RATIO) {
    const res = http.post(
      `${BASE}/api/v1/items`,
      JSON.stringify({ name: `drill-${__VU}-${__ITER}-${Date.now()}`, quantity: 1 }),
      { headers: { 'Content-Type': 'application/json' }, tags: { op: 'create' } },
    );
    report('create', res, check(res, { 'create answered 201': (r) => r.status === 201 }));
  } else {
    const res = http.get(`${BASE}/api/v1/items?page=0&size=20`, { tags: { op: 'list' } });
    report('list', res, check(res, { 'list answered 200': (r) => r.status === 200 }));
  }
}
