// B1-d: 공연 상세 조회 단독. 초당 RATE건을 DURATION_SECONDS 동안 같은 공연 상세(CONCERT_ID)로 보낸다.
// B1(b1-browse.js)은 setup에서 관리자로 공연을 만들지만, 이 시나리오는 이미 있는 공연(배포 서버는 seed-demo.sh의 시연 공연)을 받아
// 관리자 계정 없이 돈다. 공개 API라 토큰도 없다. 공연 조회(Z2) 기준선과 개선 전후 비교용이다.
// 실행: CONCERT_ID=<공연 id> RATE=50 bash loadtest/k6/run.sh scenarios/b1d-concert-detail.js (loadtest/k6/README.md)
import http from 'k6/http';
import { check } from 'k6';
import { numberEnv, requireEnv } from '../lib/env.js';
import { url } from '../lib/http.js';

const CONCERT_ID = requireEnv('CONCERT_ID');
const RATE = numberEnv('RATE', 10);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 30);

export const options = {
  scenarios: {
    detail: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', 50), maxVUs: numberEnv('MAX_VUS', 500),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'http_req_duration{api:detail}': ['p(95)<300'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<1'],
  },
};

export function setup() {
  const res = http.get(url(`/api/v1/concerts/${CONCERT_ID}`), { tags: { api: 'setup' } });
  if (res.status !== 200) {
    throw new Error(`공연 상세 확인 실패: ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
}

export default function () {
  const res = http.get(url(`/api/v1/concerts/${CONCERT_ID}`), { tags: { api: 'detail' } });
  check(res, { 'status 200': (r) => r.status === 200 });
}
