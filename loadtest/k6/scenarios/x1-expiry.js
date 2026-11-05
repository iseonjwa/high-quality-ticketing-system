// X1: 선점 시간이 1분인 회차에 선점 EXPIRING건을 미리 만들고, 그것들이 만료되는 동안 다른 회차에 선점 부하(K1-H와 같은 흐름)를 건다.
// 만료 처리 속도와 그동안의 선점 지연을 본다. 남은 만료 대상 수는 tools/watch-held.sh로 10초마다 기록한다.
// 만료 스케줄러가 켜져 있어야 한다(compose.expiry-on.yml).
// 명세: docs/design/infra/open-peak-scenarios.md X1. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf, uuid } from '../lib/tokens.js';
import { errorCodeOf, jsonHeaders, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { availableSeatIds, hold, userOf } from '../lib/booking.js';

const EXPIRING = numberEnv('EXPIRING', 5000);
const RATE = numberEnv('RATE', 100);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 300);
// 만료 대상 사용자는 측정 사용자와 겹치지 않게 뒤쪽 ID를 쓴다
const EXPIRING_USER_OFFSET = 5000000;

const holdDuration = new Trend('hold_duration', true);
const holdResults = new Counter('hold_results');

export const options = {
  setupTimeout: '600s',
  scenarios: {
    x1: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', 600), maxVUs: numberEnv('MAX_VUS', 2000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    'hold_duration': ['p(95)<1000'],
    'hold_results{result:error}': ['count<1'],
    'dropped_iterations': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  assertSigningKeys();
  const admin = adminLogin();
  // 만료 대상 회차: 선점 시간 1분(최솟값)
  const expiring = createSchedule(admin, { title: 'k6 X1 expiring', holdMinutes: 1 });
  // 측정 부하 회차: 만료와 같은 서비스·DB를 쓰지만 좌석은 겹치지 않는다
  const load = createSchedule(admin, { title: 'k6 X1 load' });
  const expiringSeats = availableSeatIds(expiring.scheduleId, userOf(userIdOf(EXPIRING_USER_OFFSET) - 1, expiring.scheduleId));
  const loadSeats = availableSeatIds(load.scheduleId, userOf(userIdOf(0) - 1, load.scheduleId));

  const started = Date.now();
  holdAll(expiring.scheduleId, expiringSeats.slice(0, EXPIRING));
  // 선점 만료 시각(1분 뒤)이 지나고 스케줄러가 한 번 돌 때까지 기다린 뒤 측정을 시작한다
  const expiresAt = started + 60 * 1000;
  console.log(`X1 만료 회차 ${expiring.scheduleId}(선점 ${EXPIRING}건, ${new Date(expiresAt).toISOString()}부터 만료), 부하 회차 ${load.scheduleId}. 기록: bash loadtest/k6/tools/watch-held.sh ${expiring.scheduleId}`);
  waitUntil(expiresAt);
  return { scheduleId: load.scheduleId, seats: loadSeats };
}

// 만료 대상 선점을 묶음으로 만든다. 사용자마다 1석
function holdAll(scheduleId, seatIds, batchSize = 100) {
  let failed = 0;
  for (let start = 0; start < seatIds.length; start += batchSize) {
    const requests = [];
    for (let i = start; i < Math.min(start + batchSize, seatIds.length); i++) {
      const user = userOf(userIdOf(EXPIRING_USER_OFFSET + i), scheduleId);
      requests.push(['POST', url(`/api/v1/schedules/${scheduleId}/reservations`), JSON.stringify({ seatIds: [seatIds[i]] }), {
        headers: Object.assign(jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
        tags: { api: 'setup' },
      }]);
    }
    for (const res of http.batch(requests)) {
      if (res.status !== 201) {
        failed++;
      }
    }
  }
  if (failed > 0) {
    console.warn(`만료 대상 선점 실패 ${failed}건 / ${seatIds.length}건`);
  }
}

// K1-H와 같은 흐름: 1~3초 고민 → 목록에서 1~2석 골라 선점, 409면 최대 2번 다시
export default function (data) {
  const user = userOf(userIdOf(exec.scenario.iterationInTest), data.scheduleId);
  sleep(1 + Math.random() * 2);
  for (let attempt = 0; attempt <= 2; attempt++) {
    const start = Math.floor(Math.random() * data.seats.length);
    const count = Math.random() < 0.5 ? 1 : 2;
    const seatIds = [data.seats[start], data.seats[(start + 1) % data.seats.length]].slice(0, count);
    const { res } = hold(data.scheduleId, user, seatIds);
    const result = res.status === 201 ? 'created' : res.status === 409 ? 'conflict' : 'error';
    holdDuration.add(res.timings.duration, { result });
    holdResults.add(1, { result, code: result === 'conflict' ? errorCodeOf(res) : String(res.status) });
    check(res, { '선점 201 또는 409': (r) => r.status === 201 || r.status === 409 });
    if (result !== 'conflict') {
      return;
    }
    sleep(0.5 + Math.random() * 0.5);
  }
}
