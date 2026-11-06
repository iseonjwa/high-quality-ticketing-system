// Q3: 입장 스케줄러를 켠 채 대기자 USERS명이 순번을 조회하고, READY가 되면 입장 토큰을 받고 끝낸다.
// 설정한 초당 입장 수(queue.admit-per-second)가 실제로 지켜지는지 본다. 초당 입장 수는 tools/watch-admitted.sh로 1초마다 기록한다.
// 명세: docs/design/infra/open-peak-scenarios.md Q3. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys } from '../lib/tokens.js';
import { dataOf, errorCodeOf, outcomeOf, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { earliestWaiter, enterAll, positionPath, waitersOfThisVu } from '../lib/queue.js';

const USERS = numberEnv('USERS', 30000);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 660);
const VUS = numberEnv('VUS', 300);
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);
const FIRST_POLL_SPREAD_SECONDS = numberEnv('FIRST_POLL_SPREAD_SECONDS', 10);

const pollResults = new Counter('poll_results');
const tokenDuration = new Trend('token_issue_duration', true);
const tokenResults = new Counter('token_results');
// READY를 본 순간부터 토큰을 받기까지(ms). 폴링 간격 때문에 READY가 된 실제 시각보다 늦게 본다
const readyToToken = new Trend('ready_to_token', true);
const pollLateness = new Trend('poll_lateness', true);

export const options = {
  setupTimeout: '600s',
  scenarios: {
    q3: { executor: 'constant-vus', vus: VUS, duration: `${DURATION_SECONDS}s` },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'token_issue_duration': ['p(95)<200'],
    'token_results{result:error}': ['count<1'],
    'http_req_failed': ['rate<0.01'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  if (USERS % VUS !== 0) {
    throw new Error(`USERS(${USERS})는 VUS(${VUS})로 나누어떨어져야 합니다`);
  }
  const schedule = createSchedule(adminLogin(), { title: 'k6 Q3', openInSeconds: OPEN_IN_SECONDS });
  waitUntil(schedule.openAt + 1000);
  // Note: 진입하는 동안에도 입장이 시작된다. 실제 오픈과 같다
  enterAll(schedule.scheduleId, USERS);
  console.log(`Q3 회차 ${schedule.scheduleId}, 대기자 ${USERS}명, VU ${VUS}개. 입장 기록: bash loadtest/k6/tools/watch-admitted.sh ${schedule.scheduleId}`);
  return { scheduleId: schedule.scheduleId };
}

let waiting = null;

export default function (data) {
  if (waiting === null) {
    waiting = waitersOfThisVu(USERS, VUS, FIRST_POLL_SPREAD_SECONDS);
  }
  const next = earliestWaiter(waiting);
  if (next === null) {
    // 이 VU의 대기자가 모두 토큰을 받았다
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
  if (res.status !== 200 || !body) {
    pollResults.add(1, { result: outcomeOf(res), code: errorCodeOf(res) });
    next.dueAt = Date.now() + 3000;
    return;
  }
  pollResults.add(1, { result: body.status === 'WAITING' ? 'waiting' : 'ready' });
  if (body.status === 'WAITING') {
    next.dueAt = Date.now() + body.nextPollAfterMillis;
    return;
  }

  // READY(또는 이미 토큰을 받은 ADMITTED): 토큰을 받고 이 대기자를 끝낸다
  const readyAt = Date.now();
  const token = http.post(url(`/api/v1/schedules/${data.scheduleId}/queue/token`), null,
    { headers: next.headers, tags: { api: 'queue_token' } });
  const tokenData = dataOf(token);
  const ok = token.status === 200 && tokenData && tokenData.entryToken;
  tokenDuration.add(token.timings.duration);
  tokenResults.add(1, { result: ok ? 'issued' : outcomeOf(token), code: ok ? '200' : errorCodeOf(token) });
  check(token, { '입장 토큰 발급': () => ok });
  if (ok) {
    readyToToken.add(Date.now() - readyAt);
    next.dueAt = null;
  } else {
    next.dueAt = Date.now() + 3000;
  }
}
