// Q1: 오픈 시각에 USERS명이 ENTER_SECONDS초 안에 대기열에 한 번씩 진입한다.
// 명세: docs/design/infra/open-peak-scenarios.md Q1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { authHeaders, errorCodeOf, messageTagOf, outcomeOf, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';

const USERS = numberEnv('USERS', 30000);
const ENTER_SECONDS = numberEnv('ENTER_SECONDS', 10);
// 연타 비율(Q1-c). 이 비율의 사용자는 같은 진입을 0.2초 간격으로 REPEAT번 보낸다
const DUP_RATIO = numberEnv('DUP_RATIO', 0);
const REPEAT = numberEnv('REPEAT', 3);
// 회차를 만들고 오픈까지 기다리는 시간. 회차 캐시 갱신 주기(30초)보다 길어야 진입이 캐시를 탄다
const OPEN_IN_SECONDS = numberEnv('OPEN_IN_SECONDS', 40);
// 기존 회차로 잴 때. 주면 회차를 만들지 않고 바로 시작한다
const SCHEDULE_ID = __ENV.SCHEDULE_ID;

const RATE = Math.ceil(USERS / ENTER_SECONDS);

const enterDuration = new Trend('enter_duration', true);
const enterResults = new Counter('enter_results');
// setup이 끝난 때부터 이 요청을 보낸 순간까지(ms). Q1-d와 같은 이름으로 남겨 같은 도구(tools/burst_buckets.py)로
// 100ms 구간별 보낸 수·거절 수를 비교한다
const enterSendOffset = new Trend('enter_send_offset', true);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    q1: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${ENTER_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', Math.min(RATE, 2000)),
      maxVUs: numberEnv('MAX_VUS', 5000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 Q1). 넘어도 측정은 끝까지 돈다
  thresholds: {
    'enter_duration': ['p(95)<200'],
    'enter_results{result:error}': ['count<1'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  if (SCHEDULE_ID) {
    return { scheduleId: Number(SCHEDULE_ID), startAt: Date.now() };
  }
  const schedule = createSchedule(adminLogin(), { title: 'k6 Q1', openInSeconds: OPEN_IN_SECONDS });
  console.log(`Q1 회차 ${schedule.scheduleId}, 오픈 ${new Date(schedule.openAt).toISOString()}, 사용자 ${USERS}명, 초당 ${RATE}명`);
  // 서버 시계와 몇백 ms 어긋날 수 있어 1초 더 기다린다
  waitUntil(schedule.openAt + 1000);
  return { scheduleId: schedule.scheduleId, startAt: Date.now() };
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  const headers = authHeaders(accessToken(userIdOf(index)));
  const attempts = Math.random() < DUP_RATIO ? REPEAT : 1;
  for (let i = 0; i < attempts; i++) {
    if (i > 0) {
      sleep(0.2);
    }
    const sentAt = Date.now();
    const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/queue`), null,
      { headers, tags: { api: 'queue_enter' } });
    // 201: 새로 진입, 200: 이미 진입(멱등). 첫 요청이 200이면 앞 실행과 사용자가 겹친 것이다
    const result = res.status === 201 ? 'joined' : res.status === 200 ? 'already' : outcomeOf(res);
    enterDuration.add(res.timings.duration, { result });
    // 응답 자체가 없으면(연결 거절·시한 초과, status 0) 본문이 없어 UNKNOWN이 되므로 따로 이름을 붙인다
    const failCode = res.status === 0 ? 'NO_RESPONSE' : errorCodeOf(res);
    const tags = { result, attempt: i === 0 ? 'first' : 'repeat', code: result === 'error' || result === 'rejected' ? failCode : String(res.status) };
    // Note: 인증 bulkhead 거절과 서비스 503은 code가 같은 SERVICE_UNAVAILABLE이라 message로 가른다
    if (result === 'error' || result === 'rejected') {
      tags.msg = messageTagOf(res);
    }
    enterResults.add(1, tags);
    enterSendOffset.add(sentAt - data.startAt, tags);
    check(res, { '진입 201 또는 200': (r) => r.status === 201 || r.status === 200 });
  }
}

export function teardown(data) {
  // 순번 확인은 Redis에서 한다: queue:{sid}:seq 값과 waiting 원소 수가 첫 요청의 201 건수와 같아야 한다.
  // Note: k6가 반복을 USERS보다 한 번 더 시작할 수 있어(300명 설정에 고유 사용자 301) USERS와 비교하지 않는다
  console.log(`Q1 확인: redis-cli GET "queue:{${data.scheduleId}}:seq", ZCARD "queue:{${data.scheduleId}}:waiting" → 기대값: 요약의 enter_results{result=joined, attempt=first} 건수(설정 ${USERS}명)`);
}
