// L3: 일반 판매(선점 부하)가 도는 중에 다른 회차의 추첨을 실행해, 추첨·배정이 같은 서비스의 선점 지연에 주는 영향을 본다.
// 선점 지연은 추첨 전(pre)·추첨 뒤 DRAW_WINDOW_SECONDS초(during)·그 뒤(post)로 나눠 기록한다.
// 명세: docs/design/infra/open-peak-scenarios.md L3. 실행: loadtest/k6/README.md
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf } from '../lib/tokens.js';
import { errorCodeOf } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { availableSeatIds, hold, userOf } from '../lib/booking.js';
import { createLottery, enterAll, registerBillingKeys } from '../lib/lottery.js';

const ENTRANTS = numberEnv('ENTRANTS', 30000);
const RATE = numberEnv('RATE', 100);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 300);
// 측정 시작 뒤 추첨 시각까지
const DRAW_AFTER_SECONDS = numberEnv('DRAW_AFTER_SECONDS', 60);
// 추첨 뒤 배정·자동결제가 몰리는 구간으로 볼 시간
const DRAW_WINDOW_SECONDS = numberEnv('DRAW_WINDOW_SECONDS', 120);
// 빌링키를 등록하면 자동결제까지 겹친다. 0이면 추첨·배정만
const WITH_BILLING = numberEnv('WITH_BILLING', 1) === 1;
const SELLER_OFFSET = 5000000;

const holdDuration = new Trend('hold_duration', true);
const holdResults = new Counter('hold_results');

export const options = {
  setupTimeout: '1200s',
  scenarios: {
    l3: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', 600), maxVUs: numberEnv('MAX_VUS', 2000),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 L3, 추첨 문서 시나리오 3): during의 p95가 pre보다 20% 넘게 늘지 않는다. 비교는 결과 정리 때 한다
  thresholds: {
    'hold_duration{phase:pre}': ['p(95)<1000'],
    'hold_duration{phase:during}': ['p(95)<1000'],
    'hold_duration{phase:post}': ['p(95)<1000'],
    'hold_results{result:error}': ['count<1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  assertSigningKeys();
  const admin = adminLogin();
  // 추첨 회차: 응모를 미리 넣고, 측정 시작 DRAW_AFTER_SECONDS초 뒤에 추첨한다
  const lotterySchedule = createSchedule(admin, { title: 'k6 L3 lottery', openInSeconds: 3600 });
  if (WITH_BILLING) {
    registerBillingKeys(ENTRANTS);
  }
  const entryStartAt = Date.now() + 15000;
  const entryEndAt = entryStartAt + Math.max(60, Math.ceil(ENTRANTS / 200)) * 1000;
  // 응모를 넣고 측정을 시작하는 시각을 entryEndAt으로 두고, 추첨은 그 뒤 DRAW_AFTER_SECONDS초
  const drawAt = entryEndAt + DRAW_AFTER_SECONDS * 1000;
  const lotteryId = createLottery(admin, lotterySchedule.scheduleId, { entryStartAt, entryEndAt, drawAt });
  waitUntil(entryStartAt + 500);
  enterAll(lotteryId, ENTRANTS);

  // 일반 판매 회차: 판매 중. 좌석은 추첨 회차와 겹치지 않는다
  const sale = createSchedule(admin, { title: 'k6 L3 sale' });
  const seats = availableSeatIds(sale.scheduleId, userOf(userIdOf(SELLER_OFFSET) - 1, sale.scheduleId));
  console.log(`L3 판매 회차 ${sale.scheduleId}, 추첨 ${lotteryId}(응모 ${ENTRANTS}명, 추첨 ${new Date(drawAt).toISOString()}). 기록: bash loadtest/k6/tools/watch-lottery.sh ${lotteryId}`);
  waitUntil(entryEndAt);
  return { scheduleId: sale.scheduleId, seats, drawAt };
}

// K1-H와 같은 흐름: 1~3초 고민 → 목록에서 1~2석 골라 선점, 409면 최대 2번 다시
export default function (data) {
  const user = userOf(userIdOf(SELLER_OFFSET + exec.scenario.iterationInTest), data.scheduleId);
  sleep(1 + Math.random() * 2);
  for (let attempt = 0; attempt <= 2; attempt++) {
    const start = Math.floor(Math.random() * data.seats.length);
    const seatIds = [data.seats[start], data.seats[(start + 1) % data.seats.length]].slice(0, Math.random() < 0.5 ? 1 : 2);
    const phase = phaseOf(data.drawAt);
    const { res } = hold(data.scheduleId, user, seatIds, { phase });
    const result = res.status === 201 ? 'created' : res.status === 409 ? 'conflict' : 'error';
    holdDuration.add(res.timings.duration, { phase, result });
    holdResults.add(1, { phase, result, code: result === 'conflict' ? errorCodeOf(res) : String(res.status) });
    check(res, { '선점 201 또는 409': (r) => r.status === 201 || r.status === 409 });
    if (result !== 'conflict') {
      return;
    }
    sleep(0.5 + Math.random() * 0.5);
  }
}

function phaseOf(drawAt) {
  const now = Date.now();
  if (now < drawAt) {
    return 'pre';
  }
  return now < drawAt + DRAW_WINDOW_SECONDS * 1000 ? 'during' : 'post';
}
