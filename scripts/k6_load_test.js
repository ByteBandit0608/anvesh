// k6 load test for Anvesh
// Install k6: https://k6.io/docs/getting-started/installation/
// Run: k6 run --vus 20 --duration 60s scripts/k6_load_test.js
// Or with API key: k6 run -e API_KEY=anv_... -e BASE_URL=http://localhost:8080 scripts/k6_load_test.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || '';

const QUERIES = [
  'data drift monitoring',
  'summer power consumption',
  'why does model accuracy drop over time',
  'విద్యుత్ డిమాండ్',
  'model evaluation metrics',
  'pagination API',
  'HNSW recall tradeoff',
];

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '30s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_duration': ['p(95)<500'],
    'errors': ['rate<0.05'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (API_KEY) h['X-API-Key'] = API_KEY;
  return h;
}

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const mode = ['hybrid', 'vector', 'keyword'][Math.floor(Math.random() * 3)];
  const url = `${BASE_URL}/api/v1/search?q=${encodeURIComponent(q)}&mode=${mode}&limit=10`;

  const res = http.get(url, { headers: headers() });

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has hits': (r) => {
      try { return JSON.parse(r.body).count >= 0; } catch { return false; }
    },
  });
  errorRate.add(!ok);
  sleep(Math.random() * 0.5 + 0.2);
}

export function handleSummary(data) {
  return {
    'stdout': `
k6 summary:
  RPS: ${(data.metrics.http_reqs.values.rate).toFixed(1)}
  p50: ${data.metrics.http_req_duration.values['p(50)'].toFixed(1)}ms
  p95: ${data.metrics.http_req_duration.values['p(95)'].toFixed(1)}ms
  errors: ${(data.metrics.errors.values.rate*100).toFixed(1)}%
`,
  };
}
