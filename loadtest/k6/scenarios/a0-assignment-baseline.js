// A0: 발제 기준선. "동시 사용자 100명이 1초 안에 모두 시작해서 각각 10번씩 요청(총 1,000회)"을 좌석 선점 API(SA 3장)에 건다.
// 지표는 발제가 정한 요청당 평균 응답 시간, 처리량, 성공률이다. 성공은 201(동기형) 또는 202(접수형, ISE-18 비교용)다.
// 반복마다 사용자와 좌석을 새로 써서 409 없이 선점 자체의 처리량을 잰다. 대기열은 거치지 않고 입장 토큰을 서명한다.
// 실행: bash loadtest/k6/run.sh scenarios/a0-assignment-baseline.js, 결과 표: python3 loadtest/k6/tools/a0-report.py (loadtest/k6/README.md)
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { adminLogin, createSchedule } from '../lib/admin.js';
import { availableSeatIds, hold, userOf } from '../lib/booking.js';

const VUS = numberEnv('VUS', 100);
const ITERATIONS = numberEnv('ITERATIONS', 10);
const SCHEDULE_ID = __ENV.SCHEDULE_ID ? Number(__ENV.SCHEDULE_ID) : 0;

// VU마다 첫 요청 직전 시각과 마지막 요청이 끝난 시각(epoch ms). 요약의 min(start) ~ max(end)가 측정 창이다
const windowStart = new Trend('a0_window_start');
const windowEnd = new Trend('a0_window_end');
const holdDuration = new Trend('a0_hold_duration', true);
const results = new Counter('a0_results');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    // 모든 VU가 같은 시각에 시작해 각자 ITERATIONS번을 쉬지 않고 보낸다
    a0: { executor: 'per-vu-iterations', vus: VUS, iterations: ITERATIONS, maxDuration: '120s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준은 측정 전에 고정한다(docs/design/infra/synthetic-data.md 5-0). 하나라도 어기면 k6가 종료 코드 99로 끝나고 요약 JSON은 그대로 남는다.
  // 요청 수가 다르거나 409가 하나라도 있으면 측정이 무효다(준비한 좌석·사용자가 겹쳤다). 오류율 1% 이상은 불합격이다.
  // Note: k6는 임계값이 걸린 하위 지표만 요약 JSON에 내보낸다. 이 조건들이 a0-report.py가 읽을 지표를 내보내는 역할도 한다
  thresholds: {
    'http_reqs{api:hold}': [`count==${VUS * ITERATIONS}`],
    'http_req_failed{api:hold}': ['rate<0.01'],
    'a0_results{status:409}': ['count==0'],
  },
};

http.setResponseCallback(http.expectedStatuses(201, 202));

export function setup() {
  assertSigningKeys();
  const scheduleId = SCHEDULE_ID || createSchedule(adminLogin(), { title: 'k6 A0' }).scheduleId;
  const needed = VUS * ITERATIONS;
  const seatIds = availableSeatIds(scheduleId, userOf(userIdOf(0) - 1, scheduleId));
  if (seatIds.length < needed) {
    throw new Error(`회차 ${scheduleId}의 빈 좌석 ${seatIds.length}석이 필요한 ${needed}석(VUS ${VUS} × ITERATIONS ${ITERATIONS})보다 적습니다. 새 회차로 다시 실행합니다`);
  }
  console.log(`A0 회차 ${scheduleId}, 빈 좌석 ${seatIds.length}석, VU ${VUS}개 × ${ITERATIONS}번 = ${needed}회`);
  return { scheduleId, seatIds: seatIds.slice(0, needed) };
}

export default function (data) {
  const number = (exec.vu.idInTest - 1) * ITERATIONS + exec.vu.iterationInScenario;
  // Note: 사용자를 반복마다 새로 쓰므로 토큰 서명도 반복마다 한다. 첫 반복의 서명은 창 시작 전이고 이후 반복의 서명 시간은 창에 들어간다(응답 시간에는 들어가지 않는다)
  const user = userOf(userIdOf(number), data.scheduleId);
  if (exec.vu.iterationInScenario === 0) {
    windowStart.add(Date.now());
  }
  const { res } = hold(data.scheduleId, user, [data.seatIds[number]]);
  const finishedAt = Date.now();
  holdDuration.add(res.timings.duration);
  results.add(1, { status: String(res.status) });
  if (exec.vu.iterationInScenario === ITERATIONS - 1) {
    windowEnd.add(finishedAt);
  }
}

export function teardown(data) {
  console.log(`A0 대사: select status, count(*) from reservations where schedule_id = ${data.scheduleId} group by status`);
}
