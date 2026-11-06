// B1: 오픈 전 공연 상세(80%)·목록(20%) 새로고침에 초당 요청을 단계적으로 올린다. 공개 API라 토큰이 없다.
// ENTER_RATE를 주면(B1-b) 같은 시간에 대기열 진입을 초당 ENTER_RATE건 같이 걸어, 조회 부하가 진입에 번지는지 본다.
// 명세: docs/design/infra/open-peak-scenarios.md B1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { authHeaders, outcomeOf, url } from '../lib/http.js';
import { addSchedule, adminLogin, createConcert, waitUntil } from '../lib/admin.js';
import { enterPath } from '../lib/queue.js';

const CONCERTS = numberEnv('CONCERTS', 20);
const SCHEDULES_PER_CONCERT = numberEnv('SCHEDULES_PER_CONCERT', 3);
const STAGES = (__ENV.STAGES || '500,1000,2000,4000').split(',').map(Number);
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 60);
const DETAIL_RATIO = numberEnv('DETAIL_RATIO', 0.8);
// B1-b: 0이면 진입 부하를 걸지 않는다
const ENTER_RATE = numberEnv('ENTER_RATE', 0);
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);

const browseDuration = new Trend('browse_duration', true);
const enterDuration = new Trend('enter_duration', true);
const results = new Counter('b1_results');

const browseStages = [];
for (const rate of STAGES) {
  browseStages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}
const totalSeconds = STAGES.length * (STAGE_SECONDS + 5);

const scenarios = {
  browse: {
    executor: 'ramping-arrival-rate', exec: 'browse', startRate: Math.min(100, STAGES[0]), timeUnit: '1s',
    stages: browseStages, preAllocatedVUs: numberEnv('PRE_VUS', 300), maxVUs: numberEnv('MAX_VUS', 3000),
  },
};
if (ENTER_RATE > 0) {
  scenarios.enter = {
    executor: 'constant-arrival-rate', exec: 'enter', rate: ENTER_RATE, timeUnit: '1s', duration: `${totalSeconds}s`,
    preAllocatedVUs: Math.min(ENTER_RATE, 1000), maxVUs: 3000,
  };
}

export const options = {
  setupTimeout: '180s',
  scenarios,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'browse_duration': ['p(95)<300'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  const admin = adminLogin();
  const concertIds = [];
  let enterScheduleId = null;
  let openAt = Date.now();
  for (let c = 0; c < CONCERTS; c++) {
    const concertId = createConcert(admin, `k6 B1 ${c}`);
    concertIds.push(concertId);
    for (let s = 0; s < SCHEDULES_PER_CONCERT; s++) {
      // 첫 공연의 첫 회차만 곧 오픈한다(B1-b의 진입 대상). 나머지는 판매 전이라 상세 화면에만 보인다
      const opensSoon = c === 0 && s === 0;
      const schedule = addSchedule(admin, concertId, { openInSeconds: opensSoon ? OPEN_IN_SECONDS : 3600 * (s + 2), startsInDays: 7 + s });
      if (opensSoon) {
        enterScheduleId = schedule.scheduleId;
        openAt = schedule.openAt;
      }
    }
  }
  if (ENTER_RATE > 0) {
    assertSigningKeys();
    // 진입이 회차 캐시를 타도록 오픈까지 기다린다(Q1과 같다)
    waitUntil(openAt + 1000);
  }
  console.log(`B1 공연 ${CONCERTS}개 × 회차 ${SCHEDULES_PER_CONCERT}개, 단계 ${STAGES.join(' → ')}/초, 진입 ${ENTER_RATE}/초(회차 ${enterScheduleId})`);
  return { concertIds, enterScheduleId };
}

export function browse(data) {
  const detail = Math.random() < DETAIL_RATIO;
  // 상세는 대상 공연(첫 공연)에 몰린다. 오픈 직전 사용자들이 같은 공연을 새로고침하는 상황이다
  const path = detail ? `/api/v1/concerts/${data.concertIds[0]}` : '/api/v1/concerts?page=0&size=20';
  const api = detail ? 'concert_detail' : 'concert_list';
  const res = http.get(url(path), { tags: { api } });
  browseDuration.add(res.timings.duration, { api });
  results.add(1, { api, result: outcomeOf(res), status: String(res.status) });
  check(res, { '조회 200': (r) => r.status === 200 });
}

export function enter(data) {
  const userId = userIdOf(exec.scenario.iterationInTest);
  const res = http.post(url(enterPath(data.enterScheduleId)), null,
    { headers: authHeaders(accessToken(userId)), tags: { api: 'queue_enter' } });
  enterDuration.add(res.timings.duration);
  results.add(1, { api: 'queue_enter', result: outcomeOf(res), status: String(res.status) });
}
