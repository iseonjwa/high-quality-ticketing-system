// S1: 회차 좌석 조회(15,000석, 응답 약 830KB) 단독. 사용자 한 명이 초당 RATE건을 DURATION_SECONDS 동안 보낸다.
// 좌석 조회 경량화(ISE-189) 단계별 비교용이다. 로컬 측정(같은 방식, 30초)과 비교하려고 대기열·선점 없이 좌석 조회만 부른다.
// 배포 서버에서 개선 전은 measure-mode.sh on loadtest/deploy/compose.seat-before.yml 로 띄운 뒤 같은 명령으로 잰다.
// 실행: SCHEDULE_ID=<회차 id> RATE=50 bash loadtest/k6/run.sh scenarios/s1-seat-query.js (loadtest/k6/README.md)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { numberEnv, requireEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, entryToken, userIdOf } from '../lib/tokens.js';
import { authHeaders, url } from '../lib/http.js';

const SCHEDULE_ID = requireEnv('SCHEDULE_ID');
const RATE = numberEnv('RATE', 10);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 30);

const results = new Counter('s1_results');

export const options = {
  scenarios: {
    seats: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', 20), maxVUs: numberEnv('MAX_VUS', 400),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{api:seats}': ['p(95)<300'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<1'],
  },
};

// 토큰은 setup에서 한 번 만든다. 입장 토큰 유효 시간(10분)이 측정 시간보다 길다
export function setup() {
  assertSigningKeys();
  const userId = userIdOf(0);
  const access = accessToken(userId);
  const entry = entryToken(userId, SCHEDULE_ID);
  const res = http.get(url(`/api/v1/schedules/${SCHEDULE_ID}/seats`),
    { headers: authHeaders(access, { 'X-Entry-Token': entry }), tags: { api: 'setup' }, timeout: '60s' });
  if (res.status !== 200) {
    throw new Error(`좌석 조회 확인 실패: ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  return { access, entry };
}

export default function (data) {
  const res = http.get(url(`/api/v1/schedules/${SCHEDULE_ID}/seats`),
    { headers: authHeaders(data.access, { 'X-Entry-Token': data.entry }), tags: { api: 'seats' }, timeout: '60s' });
  results.add(1, { status: String(res.status) });
  check(res, { 'status 200': (r) => r.status === 200 });
}
