// R1: 선점 → 결제 승인 → 예매 확정까지 걸리는 시간을 승인 속도를 올리며 잰다.
// 결제 쪽 한도가 섞이지 않게 Mock PG 지연은 기본 0이다. 확정은 outbox 릴레이 → Kafka payment.result → 예매 소비자를 거친다.
// 명세: docs/design/infra/open-peak-scenarios.md R1. 실행: loadtest/k6/README.md
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import http from 'k6/http';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { adminLogin, createSchedule } from '../lib/admin.js';
import { availableSeatIds, hold, pay, reservationStatus, userOf } from '../lib/booking.js';
import { setApproveDelay } from '../lib/mockpg.js';

const STAGES = (__ENV.STAGES || '25,50,100,200').split(',').map(Number);
// 기본값이면 좌석 약 11,600석을 쓴다
const STAGE_SECONDS = numberEnv('STAGE_SECONDS', 30);
const PG_DELAY_MS = numberEnv('PG_DELAY_MS', 0);
const CONFIRM_POLL_SECONDS = numberEnv('CONFIRM_POLL_SECONDS', 0.5);
const CONFIRM_MAX_POLLS = numberEnv('CONFIRM_MAX_POLLS', 10);

// 승인 응답을 받은 때부터 예매 조회가 CONFIRMED를 돌려준 때까지(ms). 폴링 간격만큼 올림된다
const confirmLatency = new Trend('confirm_latency', true);
const confirmResults = new Counter('confirm_results');

const stages = [];
for (const rate of STAGES) {
  stages.push({ target: rate, duration: '5s' }, { target: rate, duration: `${STAGE_SECONDS}s` });
}

export const options = {
  setupTimeout: '180s',
  scenarios: {
    r1: {
      executor: 'ramping-arrival-rate', startRate: Math.min(10, STAGES[0]), timeUnit: '1s', stages,
      preAllocatedVUs: numberEnv('PRE_VUS', 300), maxVUs: numberEnv('MAX_VUS', 3000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 R1, SA 9-4 "결제 → 예매 확정 반영 2초 이내")
  thresholds: {
    'confirm_latency': ['p(95)<2000'],
    'confirm_results{result:timeout}': ['count<1'],
    'dropped_iterations': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export function setup() {
  assertSigningKeys();
  const schedule = createSchedule(adminLogin(), { title: 'k6 R1' });
  const seats = availableSeatIds(schedule.scheduleId, userOf(userIdOf(0) - 1, schedule.scheduleId));
  setApproveDelay(PG_DELAY_MS);
  console.log(`R1 회차 ${schedule.scheduleId}, 좌석 ${seats.length}석, 단계 ${STAGES.join(' → ')}/초`);
  return { scheduleId: schedule.scheduleId, seats };
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

export function teardown() {
  setApproveDelay(0);
}
