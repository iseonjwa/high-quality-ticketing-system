// O1: 오픈 피크 한 흐름. 사용자 USERS명이 오픈 전 조회 → 대기열 진입 → 순번 폴링 → 입장 토큰 → 좌석 조회 → 선점 → 결제 → 확정 확인을 실제 순서대로 한다.
// 입장 토큰은 서명하지 않고 대기열 API로 받는다. 명세: docs/design/infra/open-peak-scenarios.md O1. 실행: loadtest/k6/README.md
//
// Note: 사용자 한 명이 수 분 동안 대기하므로 사용자마다 VU를 두면 VU가 수만 개 필요하다.
// VU 하나가 사용자 여러 명을 상태 기계로 들고, 다음 행동 시각이 가장 이른 사용자부터 한 걸음씩 진행한다.
// 한 VU 안의 요청은 순서대로 나가므로 좌석 조회처럼 느린 요청이 있으면 같은 VU의 다른 사용자가 늦어진다. 그 정도를 action_lateness로 남긴다
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf, uuid } from '../lib/tokens.js';
import { authHeaders, dataOf, errorCodeOf, jsonHeaders, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { enterPath, positionPath } from '../lib/queue.js';
import { isSoldOut, pickSeatsFromMap, reservationStatus } from '../lib/booking.js';
import { setApproveDelay } from '../lib/mockpg.js';

const USERS = numberEnv('USERS', 30000);
const VUS = numberEnv('VUS', 1000);
// 측정 시작부터 오픈까지. 이 동안 사용자들이 공연 상세를 새로고침한다
const PRE_OPEN_SECONDS = numberEnv('PRE_OPEN_SECONDS', 600);
// 오픈 뒤 진행 시간. 대기열이 비는 데 USERS ÷ 입장 속도(기본 50)초가 걸린다
const AFTER_OPEN_SECONDS = numberEnv('AFTER_OPEN_SECONDS', 900);
const ENTER_SPREAD_SECONDS = numberEnv('ENTER_SPREAD_SECONDS', 10);
const ABANDON_RATIO = numberEnv('ABANDON_RATIO', 0.1);
const MAX_RETRY = numberEnv('MAX_RETRY', 2);
const PG_DELAY_MS = numberEnv('PG_DELAY_MS', 300);

const outcomes = new Counter('o1_outcomes');
// 대기열 진입부터 입장 토큰을 받기까지(ms)
const queueWait = new Trend('queue_wait', true);
// 결제 승인 응답부터 예매 조회가 CONFIRMED가 되기까지(ms)
const confirmLatency = new Trend('confirm_latency', true);
const actionLateness = new Trend('action_lateness', true);
const stepDuration = new Trend('step_duration', true);

export const options = {
  setupTimeout: '180s',
  scenarios: {
    o1: { executor: 'constant-vus', vus: VUS, duration: `${PRE_OPEN_SECONDS + AFTER_OPEN_SECONDS}s` },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 판정 기준 초안(명세 O1). 정합성(중복 판매 0, 승인 수 = 확정 수)은 측정 뒤 대사 SQL로 본다
  thresholds: {
    'step_duration{step:hold}': ['p(95)<3000'],
    'step_duration{step:pay}': ['p(95)<3000'],
    'o1_outcomes{outcome:error}': ['count<1'],
    'confirm_latency': ['p(95)<2000'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

export function setup() {
  assertSigningKeys();
  if (USERS % VUS !== 0) {
    throw new Error(`USERS(${USERS})는 VUS(${VUS})로 나누어떨어져야 합니다`);
  }
  const schedule = createSchedule(adminLogin(), { title: 'k6 O1', openInSeconds: PRE_OPEN_SECONDS });
  setApproveDelay(PG_DELAY_MS);
  console.log(`O1 회차 ${schedule.scheduleId}(공연 ${schedule.concertId}), 사용자 ${USERS}명, VU ${VUS}개, 오픈 ${new Date(schedule.openAt).toISOString()}`);
  return { scheduleId: schedule.scheduleId, concertId: schedule.concertId, openAt: schedule.openAt };
}

let users = null;

export default function (data) {
  if (users === null) {
    users = usersOfThisVu(data);
  }
  const user = nextUser(users);
  if (user === null) {
    sleep(1);
    return;
  }
  const wait = user.nextAt - Date.now();
  if (wait > 0) {
    sleep(wait / 1000);
  }
  actionLateness.add(Math.max(0, -wait), { state: user.state });
  STEPS[user.state](user, data);
}

function usersOfThisVu(data) {
  const perVu = USERS / VUS;
  const first = (exec.vu.idInTest - 1) * perVu;
  const list = [];
  for (let i = 0; i < perVu; i++) {
    const userId = userIdOf(first + i);
    list.push({
      userId,
      access: accessToken(userId),
      // 오픈 전 들어오는 시각은 흩고, 진입은 오픈 뒤 ENTER_SPREAD_SECONDS초 안에 흩는다
      state: 'browse',
      nextAt: Date.now() + Math.random() * Math.min(PRE_OPEN_SECONDS, 60) * 1000,
      enterAt: data.openAt + 1000 + Math.random() * ENTER_SPREAD_SECONDS * 1000,
      attempt: 0,
    });
  }
  return list;
}

function nextUser(list) {
  let found = null;
  for (const item of list) {
    if (item.state !== 'done' && (found === null || item.nextAt < found.nextAt)) {
      found = item;
    }
  }
  return found;
}

function finish(user, outcome) {
  user.state = 'done';
  outcomes.add(1, { outcome });
}

function record(step, res) {
  stepDuration.add(res.timings.duration, { step });
}

const STEPS = {
  // 1. 오픈 전 상세 새로고침(10~30초 간격)
  browse(user, data) {
    if (Date.now() >= user.enterAt) {
      user.state = 'enter';
      user.nextAt = Date.now();
      return;
    }
    const res = http.get(url(`/api/v1/concerts/${data.concertId}`), { tags: { api: 'concert_detail' } });
    record('browse', res);
    user.nextAt = Math.min(Date.now() + (10 + Math.random() * 20) * 1000, user.enterAt);
  },
  // 2. 대기열 진입
  enter(user, data) {
    const res = http.post(url(enterPath(data.scheduleId)), null, { headers: authHeaders(user.access), tags: { api: 'queue_enter' } });
    record('enter', res);
    const body = dataOf(res);
    if (res.status !== 201 && res.status !== 200) {
      return retryLater(user, 3000);
    }
    user.enteredAt = Date.now();
    afterPosition(user, body);
  },
  // 3. 순번 폴링
  wait(user, data) {
    const res = http.get(url(positionPath(data.scheduleId)), { headers: authHeaders(user.access), tags: { api: 'queue_poll' } });
    record('poll', res);
    if (res.status !== 200) {
      return retryLater(user, 3000);
    }
    afterPosition(user, dataOf(res));
  },
  // 4. 입장 토큰
  token(user, data) {
    const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/queue/token`), null,
      { headers: authHeaders(user.access), tags: { api: 'queue_token' } });
    record('token', res);
    const body = dataOf(res);
    if (res.status !== 200 || !body || !body.entryToken) {
      return retryLater(user, 3000);
    }
    user.entry = body.entryToken;
    queueWait.add(Date.now() - user.enteredAt);
    user.state = 'seats';
    user.nextAt = Date.now();
  },
  // 5. 좌석 조회 → 1~3초 고민(재시도는 0.5~1초)
  seats(user, data) {
    const res = http.get(url(`/api/v1/schedules/${data.scheduleId}/seats`),
      { headers: authHeaders(user.access, { 'X-Entry-Token': user.entry }), tags: { api: 'seats' }, timeout: '10s' });
    record('seats', res);
    if (res.status !== 200) {
      return finish(user, 'error');
    }
    if (isSoldOut(res.body)) {
      return finish(user, 'soldout');
    }
    user.seatIds = pickSeatsFromMap(res.body, Math.random() < 0.5 ? 1 : 2);
    user.state = 'hold';
    user.nextAt = Date.now() + (user.attempt === 0 ? 1 + Math.random() * 2 : 0.5 + Math.random() * 0.5) * 1000;
  },
  // 6. 선점. 409면 좌석 조회부터 다시(최대 MAX_RETRY번)
  hold(user, data) {
    const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/reservations`), JSON.stringify({ seatIds: user.seatIds }), {
      headers: Object.assign(jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
      tags: { api: 'hold' },
    });
    record('hold', res);
    if (res.status === 201) {
      user.held = dataOf(res);
      if (Math.random() < ABANDON_RATIO) {
        return finish(user, 'abandoned');
      }
      user.state = 'pay';
      user.nextAt = Date.now() + (5 + Math.random() * 25) * 1000;
      return;
    }
    if (res.status === 409 && user.attempt < MAX_RETRY) {
      user.attempt++;
      user.state = 'seats';
      user.nextAt = Date.now() + (0.5 + Math.random() * 0.5) * 1000;
      return;
    }
    finish(user, res.status === 409 ? 'gave_up' : 'error');
  },
  // 7. 결제
  pay(user) {
    const res = http.post(url('/api/v1/payments'),
      JSON.stringify({ paymentToken: user.held.paymentToken, method: 'CARD', amount: user.held.totalAmount }), {
        headers: jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), tags: { api: 'payment' }, timeout: '10s',
      });
    record('pay', res);
    if (res.status !== 200) {
      const code = res.status === 503 ? errorCodeOf(res) : String(res.status);
      return finish(user, code === 'PG_UNAVAILABLE' ? 'pay_unavailable' : res.status === 202 ? 'pay_pending' : 'pay_failed');
    }
    user.approvedAt = Date.now();
    user.polls = 0;
    user.state = 'confirm';
    user.nextAt = Date.now() + 500;
  },
  // 8. 확정 확인(0.5초 간격, 최대 10번)
  confirm(user) {
    const status = reservationStatus(user, user.held.reservationId);
    if (status === 'CONFIRMED') {
      confirmLatency.add(Date.now() - user.approvedAt);
      return finish(user, 'confirmed');
    }
    if (++user.polls >= 10) {
      return finish(user, 'confirm_timeout');
    }
    user.nextAt = Date.now() + 500;
  },
};

function afterPosition(user, body) {
  if (body && body.status === 'WAITING') {
    user.state = 'wait';
    user.nextAt = Date.now() + (body.nextPollAfterMillis || 3000);
  } else {
    // READY 또는 이미 토큰을 받은 상태
    user.state = 'token';
    user.nextAt = Date.now();
  }
}

function retryLater(user, millis) {
  user.nextAt = Date.now() + millis;
}

export function teardown(data) {
  setApproveDelay(0);
  console.log(`O1 대사: select status, count(*) from reservations where schedule_id = ${data.scheduleId} group by status; `
    + `select count(*) from schedule_seats where schedule_id = ${data.scheduleId} and status = 'SOLD'`);
}
