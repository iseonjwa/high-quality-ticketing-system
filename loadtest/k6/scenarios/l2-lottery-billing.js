// L2: 추첨 직후 당첨자 배정·자동결제 폭주. setup에서 빌링키 등록과 응모를 끝내고 추첨 시각까지 기다린다.
// 측정 구간에는 PAY_RATE를 주면(L2-b) 다른 회차의 일반 결제를 같이 걸어 자동결제와의 간섭을 본다.
// 배정·결제 진행은 tools/watch-lottery.sh로 기록한다. 명세: docs/design/infra/open-peak-scenarios.md L2
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { errorCodeOf } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { availableSeatIds, hold, pay, userOf } from '../lib/booking.js';
import { createLottery, enterAll, preparedLottery, registerBillingKeys } from '../lib/lottery.js';
import { setFault, setApproveDelay } from '../lib/mockpg.js';

const ENTRANTS = numberEnv('ENTRANTS', 30000);
const SEAT_COUNT = numberEnv('SEAT_COUNT', 3000);
const WAITLIST_COUNT = numberEnv('WAITLIST_COUNT', 1000);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 660);
// 0이면 일반 결제를 걸지 않는다(L2-a)
const PAY_RATE = numberEnv('PAY_RATE', 0);
// delay: 승인 지연 PG_DELAY_MS, decline: 승인의 DECLINE_RATE를 거절.
// Note: Mock PG는 호출 종류마다 규칙을 하나만 가져 지연과 거절을 같이 걸 수 없다
const PG_MODE = __ENV.PG_MODE || 'delay';
const PG_DELAY_MS = numberEnv('PG_DELAY_MS', 300);
const DECLINE_RATE = numberEnv('DECLINE_RATE', 0.05);
// 일반 결제 사용자는 응모자와 겹치지 않게 뒤쪽 ID를 쓴다
const PAYER_OFFSET = 5000000;

const paymentDuration = new Trend('payment_duration', true);
const paymentResults = new Counter('payment_results');

export const options = {
  setupTimeout: '1200s',
  scenarios: PAY_RATE > 0
    ? { l2b: { executor: 'constant-arrival-rate', rate: PAY_RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`, preAllocatedVUs: 100, maxVUs: 1000 } }
    : { l2a: { executor: 'constant-vus', vus: 1, duration: `${DURATION_SECONDS}s` } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export function setup() {
  assertSigningKeys();
  // LOTTERY_ID를 주면 서버에서 만든 추첨을 쓴다(배포 측정). L2-b(PAY_RATE > 0)는 일반 결제 회차를 SALE_SCHEDULE_ID로 받는다.
  // 좌석·예비 수는 서버에서 만들 때 정해지므로 SEAT_COUNT·WAITLIST_COUNT는 쓰지 않는다
  const prepared = preparedLottery(PAY_RATE > 0);
  const admin = prepared ? null : adminLogin();
  const schedule = prepared ? { scheduleId: prepared.scheduleId } : createSchedule(admin, { title: 'k6 L2', openInSeconds: 3600 });
  // 빌링키는 추첨과 상관없이 사용자마다 하나라 먼저 등록한다
  const registerStarted = Date.now();
  registerBillingKeys(ENTRANTS);
  console.log(`L2 빌링키 ${ENTRANTS}명 ${Math.round((Date.now() - registerStarted) / 1000)}초`);

  // 응모 시작까지 15초를 두고 설정한다(추첨 설정은 응모 시작 전에만 받는다).
  // 마감은 응모를 다 넣을 시간(초당 약 200건 가정, 최소 60초) 뒤, 추첨은 마감 5초 뒤다
  let entryStartAt;
  let entryEndAt;
  let drawAt;
  let lotteryId;
  if (prepared) {
    ({ entryStartAt, entryEndAt, drawAt, lotteryId } = prepared);
  } else {
    entryStartAt = Date.now() + 15000;
    entryEndAt = entryStartAt + Math.max(60, Math.ceil(ENTRANTS / 200)) * 1000;
    drawAt = entryEndAt + 5000;
    lotteryId = createLottery(admin, schedule.scheduleId, {
      seatCount: SEAT_COUNT, waitlistCount: WAITLIST_COUNT, entryStartAt, entryEndAt, drawAt,
    });
  }
  if (Date.now() > entryEndAt) {
    fail(`추첨 ${lotteryId}의 응모가 이미 마감됐다(${new Date(entryEndAt).toISOString()})`);
  }
  waitUntil(entryStartAt + 500);
  enterAll(lotteryId, ENTRANTS);
  if (Date.now() > entryEndAt) {
    console.warn('응모를 넣는 데 마감 시각보다 오래 걸렸다. 마감 뒤 응모는 거절됐을 수 있다');
  }
  if (PG_MODE === 'decline') {
    setFault({ target: 'APPROVE', mode: 'DECLINE', rate: DECLINE_RATE });
  } else {
    setApproveDelay(PG_DELAY_MS);
  }

  let payer = null;
  if (PAY_RATE > 0) {
    const otherId = prepared ? prepared.saleScheduleId : createSchedule(admin, { title: 'k6 L2 general' }).scheduleId;
    payer = { scheduleId: otherId, seats: availableSeatIds(otherId, userOf(userIdOf(PAYER_OFFSET) - 1, otherId)) };
  }
  console.log(`L2 회차 ${schedule.scheduleId}, 추첨 ${lotteryId}, 응모 ${ENTRANTS}명, 추첨 ${new Date(drawAt).toISOString()}, PG ${PG_MODE}. 기록: bash loadtest/k6/tools/watch-lottery.sh ${lotteryId}`);
  // 추첨 스케줄러는 10초마다 돌므로 추첨 시각부터 측정을 시작한다
  waitUntil(drawAt);
  return { lotteryId, payer };
}

export default function (data) {
  if (!data.payer) {
    // L2-a: 부하 없이 자동결제가 끝나기를 기다린다. 진행은 watch-lottery.sh로 본다
    sleep(10);
    return;
  }
  const index = exec.scenario.iterationInTest;
  if (index >= data.payer.seats.length) {
    return;
  }
  const user = userOf(userIdOf(PAYER_OFFSET + index), data.payer.scheduleId);
  const held = hold(data.payer.scheduleId, user, [data.payer.seats[index]]);
  if (!held.data) {
    paymentResults.add(1, { result: 'hold_failed' });
    return;
  }
  const paid = pay(user, held.data);
  const result = paid.res.status === 200 ? 'approved'
    : paid.res.status === 503 && errorCodeOf(paid.res) === 'PG_UNAVAILABLE' ? 'unavailable'
      : paid.res.status === 202 ? 'pending' : 'error';
  paymentDuration.add(paid.res.timings.duration, { result });
  paymentResults.add(1, { result });
  check(paid.res, { '일반 결제 200': (r) => r.status === 200 });
}

export function teardown() {
  setApproveDelay(0);
}
