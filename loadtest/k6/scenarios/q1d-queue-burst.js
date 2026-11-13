// Q1-d: 판매 오픈 순간 1초 안에 몰리는 대기열 진입을 재현한다(ISE-217).
// Q1(q1-queue-enter.js)은 constant-arrival-rate라 1초에 N명을 그 1초 동안 고르게 나눠 보낸다.
// 여기서는 사용자 수만큼 VU를 미리 만들고, 모두 같은 출발 시각을 기다렸다가 ENTER_SPREAD_SECONDS 안에 앞쪽이 붐비게 출발시킨다.
// 명세: docs/design/infra/open-peak-scenarios.md Q1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { authHeaders, errorCodeOf, messageTagOf, outcomeOf, url } from '../lib/http.js';
import { adminLogin, createSchedule } from '../lib/admin.js';

// VU 하나가 사용자 한 명이다. VU는 메모리를 몇 MB씩 쓰므로 발생기 한 대로는 수만 명 정도가 한계다
const USERS = numberEnv('USERS', 5000);
// 출발 시각부터 마지막 사용자가 출발할 때까지의 시간(초). 0.1이면 100ms 안에 모두 출발한다
const ENTER_SPREAD_SECONDS = numberEnv('ENTER_SPREAD_SECONDS', 1);
// 회차를 만들고 오픈까지 기다리는 시간. 회차 캐시 갱신 주기(30초)보다 길어야 진입이 캐시를 탄다
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);
// SCHEDULE_ID를 줄 때(이미 열린 회차) setup이 끝나고 출발까지 기다리는 시간
const START_IN_SECONDS = numberEnv('START_IN_SECONDS', 5);
// 기존 회차로 잴 때. 주면 회차를 만들지 않는다(배포 측정은 관리자 비밀번호를 발생기에 두지 않으므로 이 값을 준다)
const SCHEDULE_ID = __ENV.SCHEDULE_ID;

const enterDuration = new Trend('enter_duration', true);
const enterResults = new Counter('enter_results');
// 정해 둔 출발 시각보다 실제로 늦게 보낸 시간. 크면 서버가 아니라 발생기(k6)가 못 따라간 것이다
const sendLag = new Trend('send_lag', true);
// 출발 시각부터 응답을 받을 때까지. 몰림이 서버에서 얼마나 늦게 풀렸는지 본다
const doneAfterStart = new Trend('done_after_start', true);
// 출발 시각부터 이 요청을 실제로 보낸 순간까지(ms). 결과 태그와 함께 남겨 100ms 구간별 보낸 수·거절 수를 본다
// (tools/burst_buckets.py). HTTP 지표의 CSV 시각은 응답이 끝난 때라 보낸 순간의 몰림 모양을 알 수 없다
const enterSendOffset = new Trend('enter_send_offset', true);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    q1d: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: 1,
      // 오픈 대기 + 출발 대기 + 몰림 + 응답 여유
      maxDuration: `${OPEN_IN_SECONDS + START_IN_SECONDS + Math.ceil(ENTER_SPREAD_SECONDS) + 120}s`,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안. 넘어도 측정은 끝까지 돈다
  thresholds: {
    'enter_duration': ['p(95)<200'],
    'enter_results{result:error}': ['count<1'],
    'http_req_failed': ['rate<0.01'],
    // 발생기가 출발 시각을 지켰는지. 넘으면 결과를 서버 한계로 읽지 않는다
    'send_lag': ['p(95)<50'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  if (SCHEDULE_ID) {
    const startAt = Date.now() + START_IN_SECONDS * 1000;
    console.log(`Q1-d 회차 ${SCHEDULE_ID}, 출발 ${new Date(startAt).toISOString()}, 사용자 ${USERS}명, ${ENTER_SPREAD_SECONDS}초 안에 출발`);
    return { scheduleId: Number(SCHEDULE_ID), startAt };
  }
  const schedule = createSchedule(adminLogin(), { title: 'k6 Q1-d', openInSeconds: OPEN_IN_SECONDS });
  // 서버 시계와 몇백 ms 어긋날 수 있어 오픈 1초 뒤에 출발한다(Q1과 같다). 기다리는 것은 각 VU가 한다
  const startAt = schedule.openAt + 1000;
  console.log(`Q1-d 회차 ${schedule.scheduleId}, 오픈 ${new Date(schedule.openAt).toISOString()}, 출발 ${new Date(startAt).toISOString()}, 사용자 ${USERS}명, ${ENTER_SPREAD_SECONDS}초 안에 출발`);
  return { scheduleId: schedule.scheduleId, startAt };
}

// 0 ~ ENTER_SPREAD_SECONDS 사이 출발 지연(ms). 앞쪽이 가장 붐비고 끝으로 갈수록 줄어드는 삼각 분포다.
// 밀도가 직선으로 줄어 절반이 앞 약 29% 구간에 몰린다. 지수 분포와 달리 끝이 있어 SPREAD를 넘지 않는다.
// Math.sqrt(u)는 0~1의 값을 1 쪽으로 밀어 뒤쪽이 붐비므로, 1에서 빼서 앞쪽이 붐비게 뒤집는다
function offsetMillis() {
  return (1 - Math.sqrt(Math.random())) * ENTER_SPREAD_SECONDS * 1000;
}

// Note: sleep은 컨테이너의 단조 시계로 재고 출발 시각은 벽시계(Date.now)로 정한다. Docker Desktop에서는
// 40초를 한 번에 자면 두 시계가 약 100ms 어긋나 모든 VU가 그만큼 늦게 출발했다. 목표 1초 전까지만 자고,
// 깨어나 남은 시간을 다시 계산해 짧게 한 번 더 잔다. 마지막 1~2초 동안의 어긋남은 수 ms 안이다
function sleepUntil(targetMillis) {
  let left = targetMillis - Date.now();
  while (left > 0) {
    sleep((left > 2000 ? left - 1000 : left) / 1000);
    left = targetMillis - Date.now();
  }
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  // 토큰은 기다리기 전에 만들어 출발 순간에는 요청만 보낸다
  const headers = authHeaders(accessToken(userIdOf(index)));
  const plannedAt = data.startAt + offsetMillis();
  sleepUntil(plannedAt);
  const sentAt = Date.now();
  sendLag.add(Math.max(0, sentAt - plannedAt));

  const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/queue`), null,
    { headers, tags: { api: 'queue_enter' } });
  doneAfterStart.add(Date.now() - data.startAt);
  // 201: 새로 진입, 200: 이미 진입(멱등). 200이 나오면 앞 실행과 사용자가 겹친 것이다(USER_ID_BASE를 바꾼다)
  const result = res.status === 201 ? 'joined' : res.status === 200 ? 'already' : outcomeOf(res);
  enterDuration.add(res.timings.duration, { result });
  // 응답 자체가 없으면(연결 거절·시한 초과, status 0) 본문이 없어 UNKNOWN이 되므로 따로 이름을 붙인다.
  // 사용자마다 새 연결을 몰림 순간에 열므로 Gateway 수락 대기열(backlog)이 넘치면 여기에 잡힌다
  const failCode = res.status === 0 ? 'NO_RESPONSE' : errorCodeOf(res);
  const tags = { result, code: result === 'error' || result === 'rejected' ? failCode : String(res.status) };
  // Note: 인증 bulkhead 거절과 서비스 503은 code가 같은 SERVICE_UNAVAILABLE이라 message로 가른다
  if (result === 'error' || result === 'rejected') {
    tags.msg = messageTagOf(res);
  }
  enterResults.add(1, tags);
  enterSendOffset.add(sentAt - data.startAt, tags);
  check(res, { '진입 201 또는 200': (r) => r.status === 201 || r.status === 200 });
}

export function teardown(data) {
  // 순번 확인은 Redis에서 한다: queue:{sid}:seq 값과 waiting 원소 수가 진입한 고유 사용자 수와 같아야 한다
  console.log(`Q1-d 확인: redis-cli GET "queue:{${data.scheduleId}}:seq", ZCARD "queue:{${data.scheduleId}}:waiting" → 기대값 ${USERS}`);
  console.log('연결 시간은 요약의 http_req_connecting{api:queue_enter}로 본다(--summary-mode=full 또는 결과 CSV)');
}
