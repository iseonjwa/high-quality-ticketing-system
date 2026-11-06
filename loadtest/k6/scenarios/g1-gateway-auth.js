// G1: 인증이 필요한 가장 가벼운 API(순번 조회)에 초당 요청을 단계적으로 올려 Gateway 인증 경로의 한도를 찾는다.
// 요청마다 JWT 검증 → Redis 블랙리스트 확인(auth-redis 차단기) → 라우팅을 거친다.
// 명세: docs/design/infra/open-peak-scenarios.md G1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { authHeaders, errorCodeOf, outcomeOf, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { enterAll, positionPath } from '../lib/queue.js';

const USERS = numberEnv('USERS', 10000);
// 단계별 초당 요청 수. 쉼표로 준다
const STAGES = (__ENV.STAGES || '1000,2000,4000,8000').split(',').map(Number);
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 60);
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);

const authDuration = new Trend('auth_path_duration', true);
const authResults = new Counter('auth_path_results');

// 단계마다 5초 동안 올리고 STAGE_SECONDS 동안 유지한다. 결과는 원자료 CSV의 시각으로 단계를 나눈다
const stages = [];
for (const rate of STAGES) {
  stages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}

export const options = {
  setupTimeout: '300s',
  scenarios: {
    g1: {
      executor: 'ramping-arrival-rate',
      startRate: Math.min(100, STAGES[0]),
      timeUnit: '1s',
      stages,
      preAllocatedVUs: numberEnv('PRE_VUS', 500),
      maxVUs: numberEnv('MAX_VUS', 3000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'auth_path_duration': ['p(95)<100'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<1'],
  },
};

// 201은 setup의 대기자 진입·회차 생성 응답이다. 폴링은 200만 정상이며 poll_results로 따로 센다
http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  const schedule = createSchedule(adminLogin(), { title: 'k6 G1', openInSeconds: OPEN_IN_SECONDS });
  waitUntil(schedule.openAt + 1000);
  enterAll(schedule.scheduleId, USERS);
  console.log(`G1 회차 ${schedule.scheduleId}, 대기자 ${USERS}명, 단계 ${STAGES.join(' → ')}/초, 단계당 ${STAGE_SECONDS}초`);
  return { scheduleId: schedule.scheduleId };
}

// Note: 요청마다 서명하면 k6 CPU를 먹어 발생기가 먼저 한계에 닿는다. VU마다 서명한 토큰을 사용자별로 기억한다
const tokens = {};

export default function (data) {
  const index = Math.floor(Math.random() * USERS);
  if (!tokens[index]) {
    tokens[index] = accessToken(userIdOf(index));
  }
  const res = http.get(url(positionPath(data.scheduleId)), { headers: authHeaders(tokens[index]), tags: { api: 'auth_path' } });
  // 503이 수 ms 만에 오면 Gateway 인증 Redis 차단기(auth-redis)가 열린 것이다. Prometheus 차단기 상태와 맞춰 본다
  const result = res.status === 200 ? 'ok' : outcomeOf(res);
  authDuration.add(res.timings.duration, { result });
  authResults.add(1, { result, status: String(res.status), code: result === 'ok' ? '200' : errorCodeOf(res) });
  check(res, { '인증 경로 200': (r) => r.status === 200 });
}
