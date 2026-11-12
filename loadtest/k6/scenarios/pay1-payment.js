// PAY1: 사용자마다 좌석 1석을 선점하고 바로 결제한다. 결제 요청 속도를 단계적으로 올려 PG 동시 수(pg-approve Bulkhead 10)의 한도를 본다.
// Note: 결제 토큰은 선점 만료 10초 전에 만료되므로 미리 만들어 두지 않고 반복 안에서 선점한다.
// 선점 경로는 이 속도에서 여유가 있어(K1-H 기준선, 첫 1분 이후 p95 20ms 미만) 결제 한도와 섞이지 않는다. 선점 지연은 api:hold로 따로 본다.
// 배포 서버에서는 관리자 계정을 발생기에 두지 않으므로 서버에서 seed-demo.sh open으로 만든 회차(SCHEDULE_ID)를 받고,
// Mock PG 지연은 서버 안에서 건다(MOCK_PG_URL을 비우면 k6는 장애 주입을 건너뛴다). 좌석을 팔기 때문에 실행마다 새 회차를 쓴다.
// 명세: docs/design/infra/open-peak-scenarios.md PAY1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { errorCodeOf } from '../lib/http.js';
import { adminLogin, createSchedule } from '../lib/admin.js';
import { availableSeatIds, hold, pay, userOf } from '../lib/booking.js';
import { setApproveDelay } from '../lib/mockpg.js';

const STAGES = (__ENV.STAGES || '20,40,80,160').split(',').map(Number);
// 기본값이면 좌석 약 14,600석을 쓴다(15,000석 안). 늘리면 좌석이 모자라 setup에서 멈춘다
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 45);
const PG_DELAY_MS = numberEnv('PG_DELAY_MS', 300);
const SCHEDULE_ID = __ENV.SCHEDULE_ID;
// mockpg.js와 같은 규칙이다. 빈 값이면 k6가 지연을 걸지 않고 서버에서 건 값을 쓴다
const DELAY_BY_K6 = __ENV.MOCK_PG_URL !== '';

const paymentDuration = new Trend('payment_duration', true);
const paymentResults = new Counter('payment_results');

const stages = [];
for (const rate of STAGES) {
  stages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}

// 단계마다 올라가는 5초 동안은 평균 속도로 어림한다(r1d-confirm.js와 같은 계산). 기본값이면 약 14,600석이다
let seatsNeeded = 0;
let previousRate = Math.min(10, STAGES[0]);
for (const rate of STAGES) {
  seatsNeeded += ((previousRate + rate) / 2) * 5 + rate * STAGE_SECONDS;
  previousRate = rate;
}
seatsNeeded = Math.ceil(seatsNeeded);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    pay1: {
      executor: 'ramping-arrival-rate', startRate: Math.min(10, STAGES[0]), timeUnit: '1s', stages,
      preAllocatedVUs: numberEnv('PRE_VUS', 200), maxVUs: numberEnv('MAX_VUS', 2000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정은 명세 PAY1을 따른다. 503 PG_UNAVAILABLE이 생기기 시작하는 속도를 원자료 CSV의 시각으로 찾는다
  thresholds: {
    'payment_duration{result:approved}': ['p(95)<1000'],
    'payment_results{result:error}': ['count<1'],
    'dropped_iterations': ['count<1'],
  },
};

// 409(좌석이 이미 선점)와 503(PG 허가 없음)은 결과로 따로 센다
http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export function setup() {
  assertSigningKeys();
  const scheduleId = SCHEDULE_ID ? Number(SCHEDULE_ID) : createSchedule(adminLogin(), { title: 'k6 PAY1' }).scheduleId;
  // 좌석 목록은 별도 사용자로 한 번만 받는다(측정 사용자와 겹치지 않게 시작값보다 앞의 ID)
  const seats = availableSeatIds(scheduleId, userOf(userIdOf(0) - 1, scheduleId));
  if (DELAY_BY_K6) {
    setApproveDelay(PG_DELAY_MS);
  }
  console.log(`PAY1 회차 ${scheduleId}${SCHEDULE_ID ? '(서버 준비)' : ''}, 빈 좌석 ${seats.length}석, 필요 약 ${seatsNeeded}석, `
    + `단계 ${STAGES.join(' → ')}/초, PG 지연 ${DELAY_BY_K6 ? `${PG_DELAY_MS}ms` : '서버에서 건 값'}`);
  // 이미 쓴 회차를 다시 주면 뒤 단계가 no_seat으로 끝나 한도를 잘못 읽는다
  if (seats.length < seatsNeeded) {
    fail(`빈 좌석이 모자란다(${seats.length} < ${seatsNeeded}). 새 회차를 쓰거나 STAGES·STAGE_SECONDS를 줄인다`);
  }
  return { scheduleId, seats };
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  if (index >= data.seats.length) {
    paymentResults.add(1, { result: 'no_seat' });
    return;
  }
  const user = userOf(userIdOf(index), data.scheduleId);
  const held = hold(data.scheduleId, user, [data.seats[index]]);
  if (!held.data) {
    paymentResults.add(1, { result: 'hold_failed', code: held.res.status === 409 ? errorCodeOf(held.res) : String(held.res.status) });
    return;
  }

  const paid = pay(user, held.data);
  const result = resultOf(paid.res);
  paymentDuration.add(paid.res.timings.duration, { result });
  paymentResults.add(1, { result, code: paid.res.status >= 400 ? errorCodeOf(paid.res) : String(paid.res.status) });
  check(paid.res, { '결제 응답 200·202 또는 503 PG_UNAVAILABLE': (r) => r.status === 200 || r.status === 202 || r.status === 503 });
}

export function teardown() {
  // 다음 측정에 지연이 남지 않게 되돌린다. 서버에서 건 지연은 측정자가 서버에서 푼다(README "Mock PG 장애 주입")
  if (DELAY_BY_K6) {
    setApproveDelay(0);
  }
}

// 200 APPROVED, 202 UNKNOWN·REQUESTED(재확인 대상), 503 PG_UNAVAILABLE(허가 없음·차단기), 그 밖은 오류
function resultOf(res) {
  if (res.status === 200) {
    return 'approved';
  }
  if (res.status === 202) {
    return 'pending';
  }
  if (res.status === 503 && errorCodeOf(res) === 'PG_UNAVAILABLE') {
    return 'unavailable';
  }
  if (res.status >= 400 && res.status < 500) {
    return 'rejected';
  }
  return 'error';
}
