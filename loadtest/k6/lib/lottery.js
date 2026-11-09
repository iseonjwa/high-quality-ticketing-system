// 추첨 준비와 응모. L1·L2·L3이 같이 쓴다.
// 추첨은 회차 일반 오픈과 응모 시작 전에만 만들 수 있다(Lottery.requireBeforeSetupDeadline). 그래서 회차 오픈은 1시간 뒤로 둔다
import http from 'k6/http';
import { fail } from 'k6';
import { accessToken, userIdOf, uuid } from './tokens.js';
import { dataOf, jsonHeaders, url } from './http.js';

// 시각은 epoch ms. drawAt은 응모 마감 뒤여야 하고, 추첨 + 60분이 공연 시작 전이어야 한다
export function createLottery(adminToken, scheduleId, options = {}) {
  const {
    sourceGrade = 'R',
    seatCount = 3000,
    waitlistCount = 1000,
    maxPerEntry = 2,
    entryStartAt,
    entryEndAt,
    drawAt,
  } = options;
  const res = http.post(url(`/api/v1/admin/schedules/${scheduleId}/lotteries`), JSON.stringify({
    sourceGrade, seatCount, waitlistCount, maxPerEntry,
    entryStartAt: new Date(entryStartAt).toISOString(),
    entryEndAt: new Date(entryEndAt).toISOString(),
    drawAt: new Date(drawAt).toISOString(),
  }), { headers: jsonHeaders(adminToken), tags: { api: 'setup' }, timeout: '60s' });
  const data = dataOf(res);
  if (res.status !== 201 || !data) {
    fail(`추첨 설정 실패: ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  return data.lotteryId;
}

// 서버에서 미리 만든 추첨을 쓸지 정한다. 관리자 비밀번호를 발생기에 두지 않는 배포 측정용이다(Q1~Q3의 SCHEDULE_ID와 같은 방식).
// LOTTERY_ID가 있으면 공개 추첨 조회로 회차와 응모·추첨 시각을 읽어 돌려주고, 셋 다 없으면 null(관리자 로그인으로 직접 준비)을 돌려준다.
// SCHEDULE_ID는 Q1~Q3처럼 "측정 대상 회차"(여기서는 추첨 회차)라는 뜻 하나만 가진다. 선택이고, 주면 추첨의 회차와 같은지 확인한다(오타 방지).
// SALE_SCHEDULE_ID는 추첨 회차와 별도로 쓰는 판매 중인 일반 판매 회차다. needsSaleSchedule이면(L2-b, L3) 반드시 받는다.
// Note: 이름을 나눈 이유는 측정 날 잘못 넣으면 측정 1회를 버리기 때문이다. 같은 이름이 시나리오마다 다른 회차를 뜻하면 그런 실수가 쉽다
// Note: LOTTERY_ID 없이 회차 ID만 있으면 실패시킨다. 추첨은 관리자만 만들 수 있어, 일부만 관리자로 준비하면 배포에서 관리자 비밀번호가 다시 필요해진다
export function preparedLottery(needsSaleSchedule) {
  const lotteryEnv = __ENV.LOTTERY_ID;
  const scheduleEnv = __ENV.SCHEDULE_ID;
  const saleEnv = __ENV.SALE_SCHEDULE_ID;
  if (!lotteryEnv && !scheduleEnv && !saleEnv) {
    return null;
  }
  if (!lotteryEnv) {
    fail(`회차 ID(SCHEDULE_ID=${scheduleEnv || ''}, SALE_SCHEDULE_ID=${saleEnv || ''})만 있다. 추첨은 관리자만 만들 수 있으므로 서버에서 만든 추첨의 LOTTERY_ID도 같이 준다`);
  }
  if (saleEnv && !needsSaleSchedule) {
    fail(`SALE_SCHEDULE_ID(${saleEnv})를 줬지만 이 실행은 일반 판매 회차를 쓰지 않는다(L1, L2-a). 빼거나 L2-b(PAY_RATE > 0)로 돌린다`);
  }
  const lottery = fetchLottery(Number(lotteryEnv));
  if (lottery.status !== 'OPEN') {
    fail(`추첨 ${lottery.lotteryId}의 상태가 OPEN이 아니다(지금 ${lottery.status}). 응모 전인 새 추첨이 필요하다`);
  }
  if (scheduleEnv && Number(scheduleEnv) !== lottery.scheduleId) {
    fail(`SCHEDULE_ID(${scheduleEnv})가 추첨 ${lottery.lotteryId}의 회차(${lottery.scheduleId})와 다르다`);
  }
  if (!needsSaleSchedule) {
    return lottery;
  }
  if (!saleEnv) {
    fail(`이 시나리오는 일반 판매 회차도 쓴다. 추첨 회차(${lottery.scheduleId})와 다른, 판매 중인 회차를 SALE_SCHEDULE_ID로 같이 준다`);
  }
  if (Number(saleEnv) === lottery.scheduleId) {
    fail(`SALE_SCHEDULE_ID(${saleEnv})가 추첨 회차와 같다. 일반 판매는 추첨 회차와 다른 회차로 잰다`);
  }
  return Object.assign({ saleScheduleId: Number(saleEnv) }, lottery);
}

// 로그인 없이 응답하는 추첨 정보(GET /api/v1/lotteries/{id}). 시각은 epoch ms로 바꿔 돌려준다
export function fetchLottery(lotteryId) {
  const res = http.get(url(`/api/v1/lotteries/${lotteryId}`), { tags: { api: 'setup' } });
  const data = dataOf(res);
  if (res.status !== 200 || !data) {
    fail(`추첨 조회 실패: ${lotteryId} ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  return {
    lotteryId: data.lotteryId,
    scheduleId: data.scheduleId,
    status: data.status,
    seatCount: data.seatCount,
    entryStartAt: epochMillis(data.entryStartAt),
    entryEndAt: epochMillis(data.entryEndAt),
    drawAt: epochMillis(data.drawAt),
  };
}

// Instant는 ISO 문자열로 온다. 서버 설정이 숫자(초)로 바뀌어도 읽는다
function epochMillis(value) {
  const millis = typeof value === 'number' ? value * 1000 : Date.parse(value);
  if (Number.isNaN(millis)) {
    fail(`추첨 시각을 읽지 못했다: ${value}`);
  }
  return millis;
}

export function entryRequest(lotteryId, userId, quantity, idempotencyKey = uuid()) {
  return ['POST', url(`/api/v1/lotteries/${lotteryId}/entries`), JSON.stringify({ quantity }), {
    headers: jsonHeaders(accessToken(userId), { 'Idempotency-Key': idempotencyKey }),
    tags: { api: 'lottery_entry' },
  }];
}

// 응모자 count명을 묶음으로 응모시킨다(1·2매 반반). 측정 대상이 아닌 준비 단계에서 쓴다
export function enterAll(lotteryId, count, batchSize = 200) {
  let failed = 0;
  for (let start = 0; start < count; start += batchSize) {
    const requests = [];
    for (let index = start; index < Math.min(start + batchSize, count); index++) {
      const [method, target, body, params] = entryRequest(lotteryId, userIdOf(index), index % 2 === 0 ? 1 : 2);
      requests.push([method, target, body, Object.assign({}, params, { tags: { api: 'setup' } })]);
    }
    for (const res of http.batch(requests)) {
      if (res.status !== 201) {
        failed++;
      }
    }
  }
  if (failed > 0) {
    fail(`응모 준비 실패 ${failed}건 / ${count}명`);
  }
}

// 자동결제에 쓸 빌링키를 등록한다. 결제 서비스가 Mock PG에서 빌링키를 받아 저장한다.
// authKey가 REJECT로 시작하면 Mock PG가 거절하므로 측정 사용자 ID를 넣는다
export function registerBillingKeys(count, batchSize = 100) {
  let failed = 0;
  for (let start = 0; start < count; start += batchSize) {
    const requests = [];
    for (let index = start; index < Math.min(start + batchSize, count); index++) {
      requests.push(['POST', url('/api/v1/payments/billing-keys'), JSON.stringify({ authKey: `k6-${userIdOf(index)}` }), {
        headers: jsonHeaders(accessToken(userIdOf(index))), tags: { api: 'setup' },
      }]);
    }
    for (const res of http.batch(requests)) {
      if (res.status !== 201 && res.status !== 200) {
        failed++;
      }
    }
  }
  if (failed > 0) {
    fail(`빌링키 등록 실패 ${failed}건 / ${count}명`);
  }
}
