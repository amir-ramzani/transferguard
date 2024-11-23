// k6 load test: constant arrival rate (default 200 rps for 5 minutes), 90% normal and 10% scam traffic.
// Run with `make load` after `make demo` (so the normal accounts have history and known payees).
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const TOKEN_URL = __ENV.TOKEN_URL || 'http://localhost:8090/transferguard/token';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '5m';
const ACCOUNTS = parseInt(__ENV.NORMAL_ACCOUNTS || '1000', 10);

export const options = {
  scenarios: {
    screening: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    'http_req_duration{name:screen}': ['p(95)<100', 'p(99)<250'],
    http_req_failed: ['rate<0.001'],
  },
};

export function setup() {
  const res = http.post(TOKEN_URL, { grant_type: 'client_credentials', client_id: 'k6', client_secret: 'k6' });
  check(res, { 'token issued': (r) => r.status === 200 });
  return { token: res.json('access_token') };
}

const pad = (n, w) => String(n).padStart(w, '0');
const rand = (min, max) => min + Math.random() * (max - min);
const money = (min, max) => rand(min, max).toFixed(2);

// Same deterministic layout as the simulator's Accounts class.
const customer = (i) => `1402-1000-${pad(i, 6)}`;
const knownPayee = (i, j) => `1402-5${pad(j, 3)}-${pad(i, 6)}`;
const freshAccount = () => `1402-${pad(Math.floor(rand(0, 9999)), 4)}-${pad(Math.floor(rand(0, 999999)), 6)}`;
const nowKL = () => new Date(Date.now() + 8 * 3600 * 1000).toISOString().replace('Z', '+08:00');

export default function (data) {
  const i = 1 + Math.floor(Math.random() * ACCOUNTS);
  const scam = Math.random() < 0.1;
  const body = {
    transactionId: `K6-${uuidv4()}`,
    sourceAccount: customer(i),
    payeeAccount: scam ? freshAccount() : knownPayee(i, Math.floor(Math.random() * 4)),
    amount: scam ? money(3000, 9000) : money(20, 600),
    currency: 'MYR',
    channel: 'MOBILE',
    deviceId: scam ? `dev-${uuidv4().slice(0, 6)}` : `dev-${pad(i, 6)}`,
    requestedAt: nowKL(),
  };
  const res = http.post(`${BASE_URL}/screenings`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
    tags: { name: 'screen' },
  });
  check(res, { 'screened (201)': (r) => r.status === 201 });
}
