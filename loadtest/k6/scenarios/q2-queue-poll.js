// Q2: 대기자 USERS명이 서버가 준 nextPollAfterMillis마다 순번을 조회한다. 입장 스케줄러는 꺼 둔다.
// 명세: docs/design/infra/open-peak-scenarios.md Q2. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys } from '../lib/tokens.js';
import { authHeaders, dataOf, errorCodeOf, outcomeOf, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { assertAdmissionStopped, earliestWaiter, enterAll, positionPath, waitersOfThisVu } from '../lib/queue.js';

const USERS = numberEnv('USERS', 30000);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 600);
// VU 하나가 대기자 여러 명의 다음 폴링 시각을 들고, 가장 이른 사람부터 부른다(lib/queue.js waitersOfThisVu)
const VUS = numberEnv('VUS', 300);
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);
// 첫 폴링을 진입 뒤 몇 초 안에 흩을지. 실제로는 진입이 10초에 걸쳐 오므로 10초
const FIRST_POLL_SPREAD_SECONDS = numberEnv('FIRST_POLL_SPREAD_SECONDS', 10);

const pollDuration = new Trend('poll_duration', true);
const pollResults = new Counter('poll_results');
// 서버가 준 간격 그대로 부르지 못한 정도(ms). 커지면 발생기(VU 수)가 모자란 것이라 VUS를 늘린다
const pollLateness = new Trend('poll_lateness', true);
const pollInterval = new Trend('poll_interval_given', true);

export const options = {
  setupTimeout: '600s',
  scenarios: {
    q2: { executor: 'constant-vus', vus: VUS, duration: `${DURATION_SECONDS}s` },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 Q2)
  thresholds: {
    'poll_duration': ['p(95)<100'],
    'poll_results{result:error}': ['count<1'],
    'http_req_failed': ['rate<0.01'],
    'poll_lateness': ['p(95)<500'],
  },
};

// 201은 setup의 대기자 진입·회차 생성 응답이다. 폴링은 200만 정상이며 poll_results로 따로 센다
http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  if (USERS % VUS !== 0) {
    throw new Error(`USERS(${USERS})는 VUS(${VUS})로 나누어떨어져야 합니다`);
  }
  const schedule = createSchedule(adminLogin(), { title: 'k6 Q2', openInSeconds: OPEN_IN_SECONDS });
  waitUntil(schedule.openAt + 1000);
  const started = Date.now();
  enterAll(schedule.scheduleId, USERS);
  console.log(`Q2 회차 ${schedule.scheduleId}, 대기자 ${USERS}명 진입 ${Math.round((Date.now() - started) / 1000)}초, VU ${VUS}개`);
  assertAdmissionStopped(schedule.scheduleId);
  return { scheduleId: schedule.scheduleId };
}

// VU마다 따로 들고 있는 대기자 상태(다음 폴링 시각, 토큰)
let waiting = null;

export default function (data) {
  if (waiting === null) {
    waiting = waitersOfThisVu(USERS, VUS, FIRST_POLL_SPREAD_SECONDS);
  }
  const next = earliestWaiter(waiting);
  if (next === null) {
    // 이 VU의 대기자가 모두 READY가 됐다. 입장 스케줄러가 켜진 경우에만 생긴다
    sleep(1);
    return;
  }
  const wait = next.dueAt - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }
  pollLateness.add(Math.max(0, -wait));

  const res = http.get(url(positionPath(data.scheduleId)), { headers: next.headers, tags: { api: 'queue_poll' } });
  const body = dataOf(res);
  const status = body && body.status;
  const result = res.status === 200 ? (status === 'WAITING' ? 'waiting' : 'ready') : outcomeOf(res);
  pollDuration.add(res.timings.duration, { result });
  pollResults.add(1, { result, code: res.status === 200 ? '200' : errorCodeOf(res) });
  check(res, { '순번 조회 200': (r) => r.status === 200 });

  if (result === 'waiting' && body.nextPollAfterMillis) {
    pollInterval.add(body.nextPollAfterMillis);
    next.dueAt = Date.now() + body.nextPollAfterMillis;
  } else if (result === 'ready') {
    next.dueAt = null;
  } else {
    // 오류면 클라이언트가 3초 뒤 다시 부른다고 본다(poll-min)
    next.dueAt = Date.now() + 3000;
  }
}
