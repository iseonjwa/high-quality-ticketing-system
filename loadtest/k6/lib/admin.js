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
  const headers = jsonHeaders(adminToken);

  const concert = post('/api/v1/admin/concerts', { title, description: 'k6' }, headers);
  const now = Date.now();
  const schedule = post(`/api/v1/admin/concerts/${concert.concertId}/schedules`, {
    venueId,
    startsAt: new Date(now + startsInDays * 86400000).toISOString(),
    openAt: new Date(now + openInSeconds * 1000).toISOString(),
    maxPerUser,
    holdMinutes,
  }, headers);
  if (withSeats) {
    // 15,000석을 한 번에 만든다. 수 초 걸리므로 시나리오의 setupTimeout을 넉넉히 둔다
    post(`/api/v1/admin/schedules/${schedule.scheduleId}/seats`, { prices }, headers, 60);
  }
  return { concertId: concert.concertId, scheduleId: schedule.scheduleId, openAt: now + openInSeconds * 1000 };
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
