// R1-d: R1(r1-confirm.js)을 배포 서버에서 돌리는 판. 선점 → 결제 승인 → 예매 확정까지 걸리는 시간을 승인 속도를 올리며 잰다.
// R1은 setup에서 관리자로 로그인해 회차를 만들지만, 발생기에는 관리자 계정을 두지 않으므로 이 시나리오는
// 서버에서 seed-demo.sh open으로 만든 회차(SCHEDULE_ID)를 받는다. 좌석을 실제로 팔기 때문에 k6 실행마다 새 회차를 쓴다.
// Mock PG 지연은 걸지 않는다. Mock PG 포트는 서버 밖에 열려 있지 않고, measure-mode.sh on으로 앱이 다시 뜨면 지연이 없는 상태다.
// 실행: SCHEDULE_ID=<회차 id> bash loadtest/k6/run.sh scenarios/r1d-confirm.js (loadtest/k6/README.md)
import { fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import http from 'k6/http';
import { numberEnv, requireEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { availableSeatIds, hold, pay, reservationStatus, userOf } from '../lib/booking.js';

const SCHEDULE_ID = Number(requireEnv('SCHEDULE_ID'));
const STAGES = (__ENV.STAGES || '25,50,100,200').split(',').map(Number);
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 30);
const CONFIRM_POLL_SECONDS = numberEnv('CONFIRM_POLL_SECONDS', 0.5);
const CONFIRM_MAX_POLLS = numberEnv('CONFIRM_MAX_POLLS', 10);

// 승인 응답을 받은 때부터 예매 조회가 CONFIRMED를 돌려준 때까지(ms). 폴링 간격만큼 올림된다
const confirmLatency = new Trend('confirm_latency', true);
const confirmResults = new Counter('confirm_results');

const stages = [];
for (const rate of STAGES) {
  stages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}

// 단계마다 올라가는 5초 동안은 평균 속도로 어림한다. 기본값이면 약 11,600석이다
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
    r1d: {
      executor: 'ramping-arrival-rate', startRate: Math.min(10, STAGES[0]), timeUnit: '1s', stages,
      preAllocatedVUs: numberEnv('PRE_VUS', 300), maxVUs: numberEnv('MAX_VUS', 3000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 R1, SA 9-4 "결제 → 예매 확정 반영 2초 이내"). R1과 같다
  thresholds: {
    'confirm_latency': ['p(95)<2000'],
    'confirm_results{result:timeout}': ['count<1'],
    'dropped_iterations': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export function setup() {
  assertSigningKeys();
  const seats = availableSeatIds(SCHEDULE_ID, userOf(userIdOf(0) - 1, SCHEDULE_ID));
  console.log(`R1-d 회차 ${SCHEDULE_ID}, 빈 좌석 ${seats.length}석, 필요 약 ${seatsNeeded}석, 단계 ${STAGES.join(' → ')}/초`);
  // 이미 쓴 회차를 다시 주면 빈 좌석이 모자라 뒤 단계가 no_seat으로 끝나 결과가 왜곡된다
  if (seats.length < seatsNeeded) {
    fail(`빈 좌석이 모자란다(${seats.length} < ${seatsNeeded}). 서버에서 seed-demo.sh open으로 새 회차를 만들어 SCHEDULE_ID로 준다`);
  }
  return { scheduleId: SCHEDULE_ID, seats };
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  if (index >= data.seats.length) {
    confirmResults.add(1, { result: 'no_seat' });
    return;
  }
  const user = userOf(userIdOf(index), data.scheduleId);
  const held = hold(data.scheduleId, user, [data.seats[index]]);
  if (!held.data) {
    confirmResults.add(1, { result: 'hold_failed' });
    return;
  }
  const paid = pay(user, held.data);
  if (paid.res.status !== 200) {
    // 승인되지 않은 결제는 확정 지연을 잴 수 없다. 결제 한도가 원인이면 PAY1에서 본다
    confirmResults.add(1, { result: 'not_approved', status: String(paid.res.status) });
    return;
  }

  const approvedAt = Date.now();
  for (let i = 0; i < CONFIRM_MAX_POLLS; i++) {
    sleep(CONFIRM_POLL_SECONDS);
    if (reservationStatus(user, held.data.reservationId) === 'CONFIRMED') {
      confirmLatency.add(Date.now() - approvedAt);
      confirmResults.add(1, { result: 'confirmed' });
      return;
    }
  }
  confirmResults.add(1, { result: 'timeout' });
}
