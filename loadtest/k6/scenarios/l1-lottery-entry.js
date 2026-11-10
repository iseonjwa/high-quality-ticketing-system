// L1: 추첨 응모 마감 직전 몰림. 응모 요청 속도를 단계적으로 올리고, 사용자의 DUP_RATIO는 같은 응모를 같은 멱등키로 두 번 보낸다.
// 명세: docs/design/infra/open-peak-scenarios.md L1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf, uuid } from '../lib/tokens.js';
import { MESSAGE_TAG_VALUES, errorCodeOf, messageTagOf, outcomeOf } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { createLottery, entryRequest, preparedLottery } from '../lib/lottery.js';

const STAGES = (__ENV.STAGES || '100,200,400,800').split(',').map(Number);
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 60);
const DUP_RATIO = numberEnv('DUP_RATIO', 0.05);
// 응모 시작까지 여유. 추첨 설정은 응모 시작 전에만 받는다
const START_IN_SECONDS = numberEnv('START_IN_SECONDS', 15);

const entryDuration = new Trend('entry_duration', true);
const entryResults = new Counter('entry_results');

const stages = [];
for (const rate of STAGES) {
  stages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}
const loadSeconds = STAGES.length * (STAGE_SECONDS + 5);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    l1: {
      executor: 'ramping-arrival-rate', startRate: Math.min(50, STAGES[0]), timeUnit: '1s', stages,
      preAllocatedVUs: numberEnv('PRE_VUS', 300), maxVUs: numberEnv('MAX_VUS', 3000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 L1, 추첨 문서 시나리오 1: 초당 100건에서 p95 300ms 이하)
  thresholds: {
    'entry_duration': ['p(95)<300'],
    'entry_results{result:error}': ['count<1'],
    'dropped_iterations': ['count<1'],
    // 항상 통과하는 집계용 기준. summary는 기준이 걸린 하위 지표만 따로 세므로, 실패를 message 출처별(예: 인증 벌크헤드 503)로 남기려고 둔다
    ...Object.fromEntries(MESSAGE_TAG_VALUES.map((tag) => [`entry_results{msg:${tag}}`, ['count>=0']])),
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201));

export function setup() {
  assertSigningKeys();
  // LOTTERY_ID를 주면 서버에서 만든 추첨을 쓴다(배포 측정). 응모 기간이 측정 시간을 덮어야 한다
  const prepared = preparedLottery(false);
  if (prepared) {
    const loadStartAt = Math.max(Date.now(), prepared.entryStartAt + 1000);
    if (loadStartAt + loadSeconds * 1000 > prepared.entryEndAt) {
      fail(`추첨 ${prepared.lotteryId}의 응모 마감(${new Date(prepared.entryEndAt).toISOString()})이 측정 끝(약 ${loadSeconds}초 뒤)보다 이르다`);
    }
    console.log(`L1 회차 ${prepared.scheduleId}, 추첨 ${prepared.lotteryId}(서버 준비), 응모 시작 ${new Date(prepared.entryStartAt).toISOString()}, 단계 ${STAGES.join(' → ')}/초. 확인: select count(*), count(distinct user_id) from lottery_entries where lottery_id = ${prepared.lotteryId}`);
    waitUntil(prepared.entryStartAt + 1000);
    return { lotteryId: prepared.lotteryId };
  }
  const admin = adminLogin();
  // 일반 오픈은 1시간 뒤(추첨 설정 조건). 응모는 측정 시간 동안 열고, 마감 뒤 1분에 추첨한다
  const schedule = createSchedule(admin, { title: 'k6 L1', openInSeconds: 3600 });
  const entryStartAt = Date.now() + START_IN_SECONDS * 1000;
  const entryEndAt = entryStartAt + (loadSeconds + 60) * 1000;
  const lotteryId = createLottery(admin, schedule.scheduleId, { entryStartAt, entryEndAt, drawAt: entryEndAt + 60000 });
  console.log(`L1 회차 ${schedule.scheduleId}, 추첨 ${lotteryId}, 단계 ${STAGES.join(' → ')}/초. 확인: select count(*), count(distinct user_id) from lottery_entries where lottery_id = ${lotteryId}`);
  waitUntil(entryStartAt + 1000);
  return { lotteryId };
}

export default function (data) {
  const userId = userIdOf(exec.scenario.iterationInTest);
  const quantity = Math.random() < 0.5 ? 1 : 2;
  const key = uuid();
  const attempts = Math.random() < DUP_RATIO ? 2 : 1;
  for (let i = 0; i < attempts; i++) {
    if (i > 0) {
      sleep(0.2);
    }
    const [, target, body, params] = entryRequest(data.lotteryId, userId, quantity, key);
    const res = http.post(target, body, params);
    const result = res.status === 201 ? 'entered' : outcomeOf(res);
    entryDuration.add(res.timings.duration, { result });
    const tags = { result, attempt: i === 0 ? 'first' : 'repeat', code: result === 'entered' ? '201' : errorCodeOf(res) };
    if (result !== 'entered') {
      tags.msg = messageTagOf(res);
    }
    entryResults.add(1, tags);
    check(res, { '응모 201': (r) => r.status === 201 });
  }
}
