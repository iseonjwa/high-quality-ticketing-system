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
