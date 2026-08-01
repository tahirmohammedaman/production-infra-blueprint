// Steady load against the local stack, through the gateway.
//
// Produces the figures in docs/cost-analysis.md — latency under a realistic mix of requests,
// and what the containers hold in memory to serve it — and checks them against the two API
// objectives in docs/slo.md. The thresholds are the objectives, so a run that fails here is a
// run whose load the system could not carry within its own promises.
//
//   make load                             60 requests/s for five minutes
//   RATE=150 DURATION=10m make load

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.API_URL || 'http://localhost:8080';
const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 60),
      timeUnit: '1s',
      duration: __ENV.DURATION || '5m',
      preAllocatedVUs: 30,
      maxVUs: 200,
    },
  },
  thresholds: {
    // api-availability: 99.5% of requests do not fail.
    http_req_failed: ['rate<0.005'],
    // api-latency: 99% of requests complete within 500 ms.
    http_req_duration: ['p(99)<500'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// A pool of existing items for the read-by-id path, created once before the load starts.
export function setup() {
  const ids = [];
  for (let i = 0; i < 50; i++) {
    const res = http.post(
      `${BASE}/api/v1/items`,
      JSON.stringify({ name: `load-seed-${Date.now()}-${i}`, quantity: 1 + (i % 9) }),
      JSON_HEADERS,
    );
    if (res.status === 201) {
      ids.push(res.json('id'));
    }
  }
  return { ids };
}

export default function (data) {
  const roll = Math.random();
  if (roll < 0.5) {
    const res = http.get(`${BASE}/api/v1/items?page=0&size=20`, { tags: { op: 'list' } });
    check(res, { 'list answered 200': (r) => r.status === 200 });
  } else if (roll < 0.7) {
    const res = http.get(`${BASE}/api/v1/inventory/summary`, { tags: { op: 'summary' } });
    check(res, { 'summary answered 200': (r) => r.status === 200 });
  } else if (roll < 0.85) {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${BASE}/api/v1/items/${id}`, { tags: { op: 'get' } });
    check(res, { 'get answered 200': (r) => r.status === 200 });
  } else {
    const res = http.post(
      `${BASE}/api/v1/items`,
      JSON.stringify({ name: `load-${__VU}-${__ITER}-${Date.now()}`, quantity: 1 }),
      Object.assign({ tags: { op: 'create' } }, JSON_HEADERS),
    );
    check(res, { 'create answered 201': (r) => r.status === 201 });
  }
}
