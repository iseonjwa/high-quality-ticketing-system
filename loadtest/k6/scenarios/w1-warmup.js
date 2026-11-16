// W1: 배포·재기동 뒤 오픈 피크 경로를 미리 실행해 JIT 컴파일을 끝내 둔다(ISE-264). 측정이 아니라 준비 작업이다.
// 경로: 공연 상세 → 대기열 진입·순번 → 좌석 조회(일부) → 선점 → 결제(Mock PG) → 확정 확인 → 취소(좌석 반환·환불).
// 예열 전용 공연 하나를 실행마다 다시 쓰고, 반복마다 취소해 좌석을 돌려준다. 그래서 운영 공연·회차와 섞이지 않고 좌석도 거의 줄지 않는다.
// 배포 서버에서는 script/deploy/warmup.sh가 부른다(deploy.sh가 배포 성공 뒤 자동으로, 측정 모드에서는 사람이 직접).
// 명세: docs/design/infra/deployment.md 5절 "배포 뒤 JIT 예열". 실행: loadtest/k6/README.md
import { fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import http from 'k6/http';
import { numberEnv } from '../lib/env.js';
import { assertSigningKeys, userIdOf, uuid } from '../lib/tokens.js';
import { addSchedule, addSeats, adminLogin, createConcert } from '../lib/admin.js';
import { availableSeatIds, hold, pay, reservationStatus, userOf } from '../lib/booking.js';
import { enterPath, positionPath } from '../lib/queue.js';
import { authHeaders, dataOf, jsonHeaders, url } from '../lib/http.js';

// 예열 전용 공연 제목. 이 제목의 공연을 찾아 다시 쓰고, 없을 때만 만든다
const WARMUP_TITLE = '[예열 전용] 배포 JIT 예열';

const RATE = numberEnv('RATE', 25);
const DURATION_SECONDS = numberEnv('DURATION_SECONDS', 120);
// 좌석 조회는 응답이 커서 비싸다(15,000석 당시 약 830KB). 반복의 이 비율만 조회한다(오픈 피크에서도 선점보다 적게 온다)
const SEAT_QUERY_RATIO = numberEnv('SEAT_QUERY_RATIO', 0.2);
const CONFIRM_POLL_SECONDS = numberEnv('CONFIRM_POLL_SECONDS', 0.5);
const CONFIRM_MAX_POLLS = numberEnv('CONFIRM_MAX_POLLS', 20);
// 취소는 공연 시작 전에만 된다. 다시 쓸 회차는 공연 시작까지 이만큼 남아 있어야 한다
const MIN_DAYS_LEFT = 2;
// 반복은 끝에 취소해 좌석을 돌려주므로 좌석을 돌려 가며 다시 쓴다. 한 바퀴가 이 시간보다 길면 앞 반복이 이미 취소를 마쳤다.
// Note: 시드 공연장이 1,500석이라(ADR 0060) 반복마다 새 좌석을 쓰면 초당 25번 × 120초 = 3,000석이 모자란다.
const SEAT_ROTATION_SECONDS = 30;
const SEATS_NEEDED = Math.ceil(RATE * SEAT_ROTATION_SECONDS);

const results = new Counter('warmup_results');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    w1: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: numberEnv('PRE_VUS', 50), maxVUs: numberEnv('MAX_VUS', 300),
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
  // 예열은 몇 건 실패해도 된다. 한 바퀴(선점 → 결제 → 확정 → 취소)도 돌지 못했을 때만 실패로 끝낸다
  thresholds: {
    'warmup_results{result:canceled}': ['count>0'],
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 202));

export function setup() {
  assertSigningKeys();
  const target = resolveSchedule();
  const seats = availableSeatIds(target.scheduleId, userOf(userIdOf(0) - 1, target.scheduleId));
  console.log(`W1 예열 공연 ${target.concertId}, 회차 ${target.scheduleId}(${target.reused ? '다시 씀' : '새로 만듦'}), `
    + `빈 좌석 ${seats.length}석을 돌려 씀, 반복 약 ${Math.ceil(RATE * DURATION_SECONDS)}번(초당 ${RATE}번 × ${DURATION_SECONDS}초)`);
  return { concertId: target.concertId, scheduleId: target.scheduleId, seats };
}

// SCHEDULE_ID·CONCERT_ID를 주면 그대로 쓴다(관리자 계정이 없는 발생기). 없으면 관리자로 예열 공연을 찾거나 만든다
function resolveSchedule() {
  if (__ENV.SCHEDULE_ID && __ENV.CONCERT_ID) {
    return { concertId: Number(__ENV.CONCERT_ID), scheduleId: Number(__ENV.SCHEDULE_ID), reused: true };
  }
  const admin = adminLogin();
  let concertId = findWarmupConcert();
  if (concertId === null) {
    concertId = createConcert(admin, WARMUP_TITLE);
  }
  const reusable = reusableSchedule(concertId);
  if (reusable !== null
      && availableSeatIds(reusable, userOf(userIdOf(0) - 1, reusable)).length >= SEATS_NEEDED) {
    return { concertId, scheduleId: reusable, reused: true };
  }
  // 이미 판매 중(1분 전 오픈)이고 공연 시작이 30일 뒤인 회차. 약 28일 동안 다음 실행이 다시 쓴다
  const schedule = addSchedule(admin, concertId,
    { openInSeconds: -60, startsInDays: 30, venueId: numberEnv('VENUE_ID', 1) });
  addSeats(admin, schedule.scheduleId);
  return { concertId, scheduleId: schedule.scheduleId, reused: false };
}

function findWarmupConcert() {
  for (let page = 0; ; page++) {
    const res = http.get(url(`/api/v1/concerts?page=${page}&size=100`), { tags: { api: 'setup' } });
    const data = dataOf(res);
    if (res.status !== 200 || !data) {
      fail(`공연 목록 조회 실패: ${res.status}`);
    }
    const found = data.content.find((concert) => concert.title === WARMUP_TITLE);
    if (found) {
      return found.concertId;
    }
    if ((page + 1) * 100 >= data.totalElements) {
      return null;
    }
  }
}

// 이미 오픈했고 공연 시작까지 MIN_DAYS_LEFT일 넘게 남은 회차 중 가장 늦게 시작하는 것
function reusableSchedule(concertId) {
  const res = http.get(url(`/api/v1/concerts/${concertId}`), { tags: { api: 'setup' } });
  const data = dataOf(res);
  if (res.status !== 200 || !data) {
    fail(`예열 공연 조회 실패: ${res.status}`);
  }
  const now = Date.now();
  const candidates = (data.schedules || [])
    .filter((s) => Date.parse(s.openAt) < now && Date.parse(s.startsAt) > now + MIN_DAYS_LEFT * 86400000)
    .sort((a, b) => Date.parse(b.startsAt) - Date.parse(a.startsAt));
  return candidates.length > 0 ? candidates[0].scheduleId : null;
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  if (data.seats.length === 0) {
    results.add(1, { result: 'no_seat' });
    return;
  }
  const scheduleId = data.scheduleId;
  const user = userOf(userIdOf(index), scheduleId);

  http.get(url(`/api/v1/concerts/${data.concertId}`), { tags: { api: 'concert_detail' } });
  http.post(url(enterPath(scheduleId)), null, { headers: authHeaders(user.access), tags: { api: 'queue_enter' } });
  http.get(url(positionPath(scheduleId)), { headers: authHeaders(user.access), tags: { api: 'queue_position' } });
  if (Math.random() < SEAT_QUERY_RATIO) {
    http.get(url(`/api/v1/schedules/${scheduleId}/seats`),
      { headers: authHeaders(user.access, { 'X-Entry-Token': user.entry }), tags: { api: 'seats' }, timeout: '30s' });
  }

  const held = hold(scheduleId, user, [data.seats[index % data.seats.length]]);
  if (!held.data) {
    results.add(1, { result: 'hold_failed', status: String(held.res.status) });
    return;
  }
  const paid = pay(user, held.data);
  if (paid.res.status !== 200) {
    // 승인되지 않은 선점은 선점 만료가 좌석을 돌려준다(배포 메모리 덮어쓰기는 만료 스케줄러를 끈다. 그때는 좌석 하나가 남는다)
    results.add(1, { result: 'not_approved', status: String(paid.res.status) });
    return;
  }
  for (let i = 0; i < CONFIRM_MAX_POLLS; i++) {
    sleep(CONFIRM_POLL_SECONDS);
    if (reservationStatus(user, held.data.reservationId) === 'CONFIRMED') {
      cancel(user, held.data.reservationId);
      return;
    }
  }
  results.add(1, { result: 'confirm_timeout' });
}

// 확정된 예매를 취소한다. 좌석·매수를 돌려주고 결제 서비스가 Mock PG로 환불한다(ReservationCancelService)
function cancel(user, reservationId) {
  const res = http.post(url(`/api/v1/reservations/${reservationId}/cancel`), null, {
    headers: jsonHeaders(user.access, { 'Idempotency-Key': uuid() }),
    tags: { api: 'cancel' },
  });
  results.add(1, { result: res.status === 202 ? 'canceled' : 'cancel_failed', status: String(res.status) });
}
