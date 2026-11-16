// O1 예외: 대기열·입장 토큰·선점·결제·취소의 예외 요청을 정해진 횟수만 보내고, 응답이 기대 상태 코드·오류 코드 표와 맞는지 본다(ISE-289).
// 부하가 아니라 정합성 확인용이다. 처리량 숫자에 섞지 않도록 O1과 따로 돌린다. 기대 코드 표와 근거: loadtest/k6/README.md "O1 예외"
// 실행: ITERATIONS=1 bash loadtest/k6/run.sh scenarios/o1-exceptions.js
// 판정: 사례마다 exception_cases{case:<이름>, result:pass|fail}를 하나씩 남긴다. fail이 하나라도 있으면 임계값이 실패한다.
//
// Note: 대기열 사례 말고는 입장 토큰을 대기열 API로 받지 않고 서명해서 만든다(A0·R1과 같다, TOKEN_MODE=sign).
// 대기열 입장 속도(초당 50명)에 묶이지 않고 사례를 바로 만들려는 것이다. 서명한 토큰에도 jti가 있어 1회 사용 검사(ADR 0053)는 같게 걸린다
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { numberEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, entryToken, userIdOf, uuid } from '../lib/tokens.js';
import { authHeaders, dataOf, errorCodeOf, jsonHeaders, url } from '../lib/http.js';
import { adminLogin, createSchedule } from '../lib/admin.js';
import { enterPath } from '../lib/queue.js';
import { availableSeatIds, cancelReservation, reservationStatus, userOf } from '../lib/booking.js';

const ITERATIONS = numberEnv('ITERATIONS', 1);
// 한 번 돌 때 쓰는 사용자 번호 칸. 사례마다 새 사용자를 써서 앞 사례의 매수·토큰이 섞이지 않게 한다
const USERS_PER_ITERATION = 10;
// 한 번 돌 때 쓰는 좌석 수(A 2석, B 0석, C 4석 + 한도 확인 1석 + 취소 뒤 4석). 넉넉히 둔다
const SEATS_PER_ITERATION = 16;
// QUEUE_NOT_READY를 만들려고 시험 사용자 앞에 세우는 대기자 수. 입장 속도 초당 50명이면 앞사람이 다 들어가는 데 6초다
const QUEUE_AHEAD = 300;
const CONFIRM_WAIT_SECONDS = 20;

const cases = new Counter('exception_cases');

export const options = {
  setupTimeout: '300s',
  scenarios: {
    exceptions: { executor: 'per-vu-iterations', vus: 1, iterations: ITERATIONS, maxDuration: '600s' },
  },
  thresholds: {
    'exception_cases{result:fail}': ['count<1'],
    'exception_cases{result:pass}': ['count>0'],
  },
};

// 예외 요청은 4xx가 정상이라 http_req_failed에 섞이지 않게 기대 응답으로 둔다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 499 }));

export function setup() {
  assertSigningKeys();
  const admin = adminLogin();
  // 판매 전 회차(1시간 뒤 오픈)와 판매 중 회차. 판매 중 회차는 회차 캐시(30초)에 아직 없어 입장 스케줄러가 바로 들이지 않을 수 있다
  const notOpen = createSchedule(admin, { title: 'k6 O1 예외 판매 전', openInSeconds: 3600 });
  const onSale = createSchedule(admin, { title: 'k6 O1 예외 판매 중', openInSeconds: -60 });
  const helper = userOf(userIdOf(0) - 1, notOpen.scheduleId);
  const notOpenSeats = availableSeatIds(notOpen.scheduleId, helper);
  const onSaleSeats = availableSeatIds(onSale.scheduleId, userOf(userIdOf(0) - 1, onSale.scheduleId));
  if (onSaleSeats.length < ITERATIONS * SEATS_PER_ITERATION) {
    throw new Error(`빈 좌석 ${onSaleSeats.length}석이 필요한 ${ITERATIONS * SEATS_PER_ITERATION}석보다 적습니다`);
  }
  console.log(`O1 예외: 판매 전 회차 ${notOpen.scheduleId}, 판매 중 회차 ${onSale.scheduleId}, ${ITERATIONS}번`);
  return {
    notOpenId: notOpen.scheduleId,
    onSaleId: onSale.scheduleId,
    notOpenSeat: notOpenSeats[0],
    onSaleSeats: onSaleSeats.slice(0, ITERATIONS * SEATS_PER_ITERATION),
  };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const base = iteration * USERS_PER_ITERATION;
  const seats = data.onSaleSeats.slice(iteration * SEATS_PER_ITERATION, (iteration + 1) * SEATS_PER_ITERATION);
  const results = [];
  // 판정을 남기고 통과 여부를 돌려준다. 준비 단계가 실패하면 그 뒤 사례는 건너뛴다
  const expect = (name, res, status, code, extra) => {
    const result = judge(name, res, status, code, extra);
    results.push(result);
    return result.ok;
  };

  queueCases(data, base, expect);
  gatewayCases(data, base, seats, expect);
  const a = person(base + 3, data.onSaleId);
  holdPayCancelCases(data, a, person(base + 4, data.onSaleId), seats, expect);
  limitCases(data, person(base + 5, data.onSaleId), seats.slice(4), expect);

  console.log(`O1 예외 ${iteration + 1}번째: 통과 ${results.filter((r) => r.ok).length}/${results.length}\n`
    + results.map((r) => `${r.ok ? '통과' : '실패'} ${r.name}: 기대 ${r.expected}, 실제 ${r.actual}`).join('\n'));
}

// 사용자 한 명. 입장 토큰은 선점마다 새로 서명한다(newEntry)
function person(index, scheduleId) {
  const userId = userIdOf(index);
  return { userId, access: accessToken(userId), entry: entryToken(userId, scheduleId), scheduleId };
}

function newEntry(user) {
  user.entry = entryToken(user.userId, user.scheduleId);
}

// ---------- 대기열 ----------
function queueCases(data, base, expect) {
  const early = person(base, data.notOpenId);
  expect('E01 오픈 전 대기열 진입', post(enterPath(data.notOpenId), null, early), 409, 'SALE_NOT_OPEN');

  const stranger = person(base + 1, data.onSaleId);
  expect('E02 진입 없이 입장 토큰 요청', post(`/api/v1/schedules/${data.onSaleId}/queue/token`, null, stranger), 404, 'QUEUE_NOT_JOINED');

  // 앞에 대기자를 세운 뒤 진입하고 곧바로 토큰을 달라고 한다. 앞사람이 다 들어가기 전이라 아직 차례가 아니다
  fillQueue(data.onSaleId, base);
  const waiter = person(base + 2, data.onSaleId);
  const entered = post(enterPath(data.onSaleId), null, waiter);
  if (entered.status !== 201) {
    fail(`시험 사용자 대기열 진입 실패: ${entered.status} ${(entered.body || '').substring(0, 200)}`);
  }
  expect('E03 차례 전 입장 토큰 요청', post(`/api/v1/schedules/${data.onSaleId}/queue/token`, null, waiter), 409, 'QUEUE_NOT_READY');
}

// 시험 사용자와 겹치지 않게 사용자 번호 끝쪽(2,000,000 이후)을 대기자로 쓴다
function fillQueue(scheduleId, base) {
  const requests = [];
  for (let i = 0; i < QUEUE_AHEAD; i++) {
    const userId = userIdOf(2000000 + base * QUEUE_AHEAD + i);
    requests.push(['POST', url(enterPath(scheduleId)), null, { headers: authHeaders(accessToken(userId)), tags: { api: 'setup' } }]);
  }
  const failed = http.batch(requests).filter((res) => res.status !== 201 && res.status !== 200).length;
  if (failed > 0) {
    fail(`대기자 ${QUEUE_AHEAD}명 중 ${failed}명 진입 실패`);
  }
}

// ---------- Gateway·선점 입력 ----------
function gatewayCases(data, base, seats, expect) {
  const user = person(base + 6, data.onSaleId);
  const noToken = { userId: user.userId, access: user.access, entry: null };
  expect('E04 입장 토큰 없이 좌석 조회', http.get(url(`/api/v1/schedules/${data.onSaleId}/seats`),
    { headers: authHeaders(user.access), tags: { api: 'seats' } }), 403, 'ENTRY_TOKEN_INVALID');
  expect('E05 입장 토큰 없이 선점', holdRequest(data.onSaleId, noToken, [seats[0]]), 403, 'ENTRY_TOKEN_INVALID');

  const early = person(base + 7, data.notOpenId);
  expect('E06 오픈 전 선점', holdRequest(data.notOpenId, early, [data.notOpenSeat]), 409, 'SALE_NOT_OPEN');

  expect('E07 같은 좌석을 두 번 담은 선점', holdRequest(data.onSaleId, user, [seats[0], seats[0]]), 400, 'SEAT_COUNT_MISMATCH');
  expect('E08 매수 상한(4)을 넘긴 선점', holdRequest(data.onSaleId, user, seats.slice(10, 15)), 400, 'SEAT_COUNT_MISMATCH');
}

// ---------- 선점 → 결제 → 확정 → 취소 ----------
function holdPayCancelCases(data, a, other, seats, expect) {
  const held = holdRequest(data.onSaleId, a, [seats[0]]);
  if (!expect('E09 정상 선점(준비)', held, 201, null)) {
    return;
  }
  const reservation = dataOf(held);
  expect('E10 이미 쓴 입장 토큰으로 다른 좌석 선점', holdRequest(data.onSaleId, a, [seats[1]]), 403, 'ENTRY_TOKEN_USED');
  expect('E11 결제 전(HELD) 예매 취소', cancelReservation(a, reservation.reservationId), 409, 'RESERVATION_NOT_CANCELABLE');
  expect('E12 금액을 바꾼 결제', payRequest(a, reservation, reservation.totalAmount + 1000), 400, 'PAYMENT_AMOUNT_MISMATCH');

  const paid = payRequest(a, reservation, reservation.totalAmount);
  if (!expect('E13 정상 결제(준비)', paid, 200, null)) {
    return;
  }
  // Note: 새 멱등키로 같은 내용을 다시 보내면 PAYMENT_ALREADY_EXISTS가 아니라 기존 결제에 연결해 같은 결제를 돌려준다
  // (PaymentApprovalService.respondToExisting, 결정 0019). PAYMENT_ALREADY_EXISTS는 사용자·금액·수단이 다른 결제일 때다
  const again = payRequest(a, reservation, reservation.totalAmount);
  expect('E14 같은 결제를 새 멱등키로 다시 보냄', again, 200, null,
    () => samePayment(paid, again));

  expect('E15 확정 대기(준비)', confirmed(a, reservation.reservationId), 200, null);
  expect('E16 멱등키 없이 취소', http.post(url(`/api/v1/reservations/${reservation.reservationId}/cancel`), null,
    { headers: authHeaders(a.access), tags: { api: 'cancel' } }), 400, 'VALIDATION_FAILED');
  expect('E17 남의 예매 취소', cancelReservation(other, reservation.reservationId), 404, 'NOT_FOUND');
  const canceled = cancelReservation(a, reservation.reservationId);
  expect('E18 확정 예매 취소', canceled, 202, null);
  // Note: 취소는 예매 상태로 멱등을 지킨다. 이미 CANCELED면 409가 아니라 같은 202다(ReservationCancelService)
  expect('E19 같은 예매 두 번 취소', cancelReservation(a, reservation.reservationId), 202, null);
}

// ---------- 매수 한도 ----------
function limitCases(data, c, seats, expect) {
  const first = holdRequest(data.onSaleId, c, seats.slice(0, 4));
  if (!expect('E20 4매 선점(준비)', first, 201, null)) {
    return;
  }
  newEntry(c);
  expect('E21 한도(4) 넘는 추가 선점', holdRequest(data.onSaleId, c, [seats[4]]), 409, 'PURCHASE_LIMIT_EXCEEDED');

  // 취소하면 한도가 돌아오는지(C6). 4매를 결제·확정·취소한 뒤 새 토큰으로 4매를 다시 잡는다
  const reservation = dataOf(first);
  if (!expect('E22 4매 결제(준비)', payRequest(c, reservation, reservation.totalAmount), 200, null)
    || !expect('E23 4매 확정 대기(준비)', confirmed(c, reservation.reservationId), 200, null)
    || !expect('E24 4매 취소(준비)', cancelReservation(c, reservation.reservationId), 202, null)) {
    return;
  }
  newEntry(c);
  expect('E25 취소 뒤 다시 4매 선점', holdRequest(data.onSaleId, c, seats.slice(5, 9)), 201, null);
}

// ---------- 요청과 판정 ----------
function post(path, body, user) {
  return http.post(url(path), body, { headers: authHeaders(user.access), tags: { api: 'exception' } });
}

function holdRequest(scheduleId, user, seatIds) {
  const headers = jsonHeaders(user.access, { 'Idempotency-Key': uuid() });
  if (user.entry) {
    headers['X-Entry-Token'] = user.entry;
  }
  return http.post(url(`/api/v1/schedules/${scheduleId}/reservations`), JSON.stringify({ seatIds }), { headers, tags: { api: 'hold' } });
}

// Note: pgProvider MOCK은 o1-open-peak.js pay와 같은 이유다(배포 기본 PG가 토스라 없으면 400)
function payRequest(user, reservation, amount) {
  return http.post(url('/api/v1/payments'),
    JSON.stringify({ paymentToken: reservation.paymentToken, method: 'CARD', amount, pgProvider: 'MOCK' }),
    { headers: jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), tags: { api: 'payment' }, timeout: '10s' });
}

function samePayment(first, second) {
  const a = dataOf(first);
  const b = dataOf(second);
  return a !== null && b !== null && a.paymentId === b.paymentId;
}

// 예매가 CONFIRMED가 될 때까지 1초마다 본다. 판정에 쓰려고 응답 모양({status})으로 돌려준다
function confirmed(user, reservationId) {
  for (let i = 0; i < CONFIRM_WAIT_SECONDS; i++) {
    if (reservationStatus(user, reservationId) === 'CONFIRMED') {
      return { status: 200, body: '' };
    }
    sleep(1);
  }
  return { status: 0, body: `${CONFIRM_WAIT_SECONDS}초 안에 CONFIRMED가 아님` };
}

// 상태 코드와 오류 코드(실패 응답 { status, code, message, traceId }의 code)가 둘 다 맞아야 통과다. code가 null이면 상태 코드만 본다
function judge(name, res, status, code, extra) {
  const actualCode = res.status >= 400 ? errorCodeOf(res) : '-';
  const ok = res.status === status && (code === null || actualCode === code) && (extra === undefined || extra());
  cases.add(1, { case: name.split(' ')[0], result: ok ? 'pass' : 'fail' });
  check(res, { [name]: () => ok });
  const result = { name, ok, expected: `${status} ${code || ''}`.trim(), actual: `${res.status} ${actualCode}` };
  if (!ok) {
    result.actual += ` ${(res.body || '').substring(0, 160)}`;
  }
  return result;
}
