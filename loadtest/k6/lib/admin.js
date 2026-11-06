// 측정 데이터 준비: 관리자 로그인, 공연·회차·좌석 생성. setup()에서만 부른다.
// 절차는 BookingFlowSystemTest와 같다. 측정마다 새 회차를 만들어 앞 실행의 선점·대기열과 섞이지 않게 한다
import http from 'k6/http';
import { fail, sleep } from 'k6';
import { dataOf, jsonHeaders, url } from './http.js';

const DEFAULT_PRICES = [
  { grade: 'VIP', price: 165000 },
  { grade: 'R', price: 143000 },
  { grade: 'S', price: 112000 },
];

// 관리자 계정은 run.sh가 저장소 루트 .env의 ADMIN_EMAIL·ADMIN_PASSWORD를 넘긴다
export function adminLogin() {
  const res = http.post(url('/api/v1/auth/login'),
    JSON.stringify({ email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { api: 'setup' } });
  const data = dataOf(res);
  if (res.status !== 200 || !data || !data.accessToken) {
    fail(`관리자 로그인 실패: ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  return data.accessToken;
}

// 회차를 만든다. openInSeconds가 음수면 이미 판매 중이다. 시드 공연장(venueId 1, 15,000석)을 쓴다
export function createSchedule(adminToken, options = {}) {
  const {
    title = 'k6 loadtest',
    openInSeconds = -60,
    startsInDays = 7,
    maxPerUser = 4,
    holdMinutes = 5,
    venueId = 1,
    withSeats = true,
    prices = DEFAULT_PRICES,
  } = options;
  const concertId = createConcert(adminToken, title);
  const schedule = addSchedule(adminToken, concertId, { openInSeconds, startsInDays, maxPerUser, holdMinutes, venueId });
  if (withSeats) {
    addSeats(adminToken, schedule.scheduleId, prices);
  }
  return Object.assign({ concertId }, schedule);
}

export function createConcert(adminToken, title) {
  return post('/api/v1/admin/concerts', { title, description: 'k6' }, jsonHeaders(adminToken)).concertId;
}

export function addSchedule(adminToken, concertId, options = {}) {
  const { openInSeconds = -60, startsInDays = 7, maxPerUser = 4, holdMinutes = 5, venueId = 1 } = options;
  const now = Date.now();
  const schedule = post(`/api/v1/admin/concerts/${concertId}/schedules`, {
    venueId,
    startsAt: new Date(now + startsInDays * 86400000).toISOString(),
    openAt: new Date(now + openInSeconds * 1000).toISOString(),
    maxPerUser,
    holdMinutes,
  }, jsonHeaders(adminToken));
  return { scheduleId: schedule.scheduleId, openAt: now + openInSeconds * 1000 };
}

// 15,000석을 한 번에 만든다. 수 초 걸리므로 시나리오의 setupTimeout을 넉넉히 둔다
export function addSeats(adminToken, scheduleId, prices = DEFAULT_PRICES) {
  post(`/api/v1/admin/schedules/${scheduleId}/seats`, { prices }, jsonHeaders(adminToken), 60);
}

// 오픈 시각까지 기다린다. Note: 대기열의 회차 캐시는 30초마다 갱신되므로(queue.schedule-cache-refresh),
// 오픈 전 30초 넘게 기다려야 진입 요청이 DB 대신 캐시를 탄다
export function waitUntil(epochMillis) {
  const remaining = epochMillis - Date.now();
  if (remaining > 0) {
    sleep(remaining / 1000);
  }
}

function post(path, body, headers, timeoutSeconds = 10) {
  const res = http.post(url(path), JSON.stringify(body), { headers, tags: { api: 'setup' }, timeout: `${timeoutSeconds}s` });
  if (res.status < 200 || res.status >= 300) {
    fail(`준비 실패: POST ${path} ${res.status} ${(res.body || '').substring(0, 200)}`);
  }
  return dataOf(res) || {};
}
