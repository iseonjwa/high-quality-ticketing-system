// 좌석 목록, 선점, 결제, 확정 확인. PAY1·R1·X1·O1이 같이 쓴다.
// 좌석 선점 K1(reservation-service/k6/k1-seathold.js)과 같은 요청을 보낸다
import http from 'k6/http';
import { fail } from 'k6';
import { accessToken, entryToken, uuid } from './tokens.js';
import { authHeaders, dataOf, jsonHeaders, url } from './http.js';

const AVAILABLE = '"available":true';

// 사용자 한 명의 요청 헤더 재료. Access 토큰과 입장 토큰을 같이 만든다
export function userOf(userId, scheduleId) {
  return { userId, access: accessToken(userId), entry: entryToken(userId, scheduleId) };
}

// setup에서 좌석 조회 한 번으로 선택 가능한 좌석 ID를 응답 순서대로 모은다.
// Note: 15,000석 응답(약 830KB)을 JSON.parse하지 않고 문자열 검색으로 seatId만 꺼낸다(k1-seathold.js와 같은 이유)
export function availableSeatIds(scheduleId, user) {
  const res = http.get(url(`/api/v1/schedules/${scheduleId}/seats`),
    { headers: seatHeaders(user), tags: { api: 'setup' }, timeout: '60s' });
  if (res.status !== 200) {
    fail(`좌석 조회 실패: ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  const body = res.body;
  const ids = [];
  let position = body.indexOf(AVAILABLE);
  while (position >= 0) {
    const start = body.lastIndexOf('{', position);
    const end = body.indexOf('}', position);
    const match = /"seatId":(\d+)/.exec(body.substring(start, end));
    if (match) {
      ids.push(Number(match[1]));
    }
    position = body.indexOf(AVAILABLE, position + AVAILABLE.length);
  }
  return ids;
}

// 좌석 조회 응답에서 선택 가능한 좌석 count개를 고른다. k1-seathold.js의 pickSeats와 같은 규칙이다.
// hotRatio 비율의 사용자는 응답 앞쪽 hotShare(인기 구역 근처)에서 고르고, 두 번째 좌석은 바로 다음 선택 가능 좌석이다
export function pickSeatsFromMap(body, count, hotRatio = 0.7, hotShare = 0.1) {
  const hot = Math.random() < hotRatio;
  const range = hot ? Math.floor(body.length * hotShare) : body.length;
  let position = body.indexOf(AVAILABLE, Math.floor(Math.random() * range));
  if (position < 0) {
    position = body.indexOf(AVAILABLE);
  }
  const seatIds = [];
  while (position >= 0 && seatIds.length < count) {
    const start = body.lastIndexOf('{', position);
    const end = body.indexOf('}', position);
    const match = /"seatId":(\d+)/.exec(body.substring(start, end));
    if (match && seatIds.indexOf(Number(match[1])) < 0) {
      seatIds.push(Number(match[1]));
    }
    position = body.indexOf(AVAILABLE, position + AVAILABLE.length);
  }
  return seatIds;
}

export function isSoldOut(body) {
  return body.indexOf(AVAILABLE) < 0;
}

export function hold(scheduleId, user, seatIds, tags = {}) {
  const res = http.post(url(`/api/v1/schedules/${scheduleId}/reservations`), JSON.stringify({ seatIds }), {
    headers: Object.assign(jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
    tags: Object.assign({ api: 'hold' }, tags),
  });
  return { res, data: res.status === 201 ? dataOf(res) : null };
}

// 결제 승인. 본문 금액은 선점 응답의 totalAmount다(결제 서비스가 토큰의 금액과 비교한다)
export function pay(user, held, tags = {}) {
  const res = http.post(url('/api/v1/payments'),
    JSON.stringify({ paymentToken: held.paymentToken, method: 'CARD', amount: held.totalAmount }), {
      headers: jsonHeaders(user.access, { 'Idempotency-Key': uuid() }),
      tags: Object.assign({ api: 'payment' }, tags),
      // Gateway 3초보다 길게 두어 504를 서버 응답으로 받는다
      timeout: '10s',
    });
  return { res, data: dataOf(res) };
}

export function reservationStatus(user, reservationId) {
  const res = http.get(url(`/api/v1/reservations/${reservationId}`),
    { headers: authHeaders(user.access), tags: { api: 'reservation_get' } });
  const data = dataOf(res);
  return data ? data.status : null;
}

function seatHeaders(user) {
  return authHeaders(user.access, { 'X-Entry-Token': user.entry });
}
