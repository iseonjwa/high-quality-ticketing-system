// 시나리오 K1: 대기열이 초당 RATE명씩 입장시킨 사용자가 좌석을 조회하고 선점한다.
// 설계: docs/design/reservation/k6-seathold-bottleneck-plan.md 1절. 실행 방법: 같은 폴더 README.md
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const SCHEDULE_ID = __ENV.SCHEDULE_ID;
const USERS_CSV = __ENV.USERS_CSV || '/scripts/data/users.csv';
const MODEL = __ENV.MODEL || 'open';
const RATE = Number(__ENV.RATE || 100);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 300);
const CLOSED_VUS = Number(__ENV.CLOSED_VUS || 100);
const THINK_MIN = Number(__ENV.THINK_MIN || 1);
const THINK_MAX = Number(__ENV.THINK_MAX || 3);
const MAX_RETRY = Number(__ENV.MAX_RETRY || 2);
const HOT_RATIO = Number(__ENV.HOT_RATIO || 0.7);
const HOT_SHARE = Number(__ENV.HOT_SHARE || 0.1);
// csv: 미리 받은 토큰을 읽는다. sign: local 스택 전용으로 k6가 반복마다 토큰을 직접 서명한다(README 2절)
const TOKEN_MODE = __ENV.TOKEN_MODE || 'csv';
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 1000000);

if (!SCHEDULE_ID) {
  throw new Error('SCHEDULE_ID 환경변수가 필요합니다');
}

// 한 줄에 사용자 한 명: accessToken,entryToken. 첫 줄은 머리글이다
const users = TOKEN_MODE === 'sign' ? [] : new SharedArray('users', () => open(USERS_CSV)
  .split(/\r?\n/)
  .slice(1)
  .filter((line) => line.trim() !== '')
  .map((line) => {
    const [accessToken, entryToken] = line.split(',');
    return { accessToken: accessToken.trim(), entryToken: entryToken.trim() };
  }));

// 좌석 조회는 http_req_duration{api:seats}로도 보이지만, 판매·매진 뒤 구간은 응답을 받아야 알 수 있어 따로 기록한다
const seatMapDuration = new Trend('seat_map_duration', true);
const holdDuration = new Trend('hold_duration', true);
const holdResults = new Counter('hold_results');
const usersFinished = new Counter('users_finished');
const requestsPerUser = new Trend('requests_per_user');

const scenario = MODEL === 'closed'
  ? {
    // 비교용 닫힌 모델: 서버가 느려지면 요청도 줄어든다
    executor: 'constant-vus',
    vus: CLOSED_VUS,
    duration: `${DURATION_SECONDS}s`,
  }
  : {
    // 기본 열린 모델: 응답 속도와 상관없이 초당 RATE명이 들어온다
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: `${DURATION_SECONDS}s`,
    preAllocatedVUs: Number(__ENV.PRE_VUS || 600),
    maxVUs: Number(__ENV.MAX_VUS || 2000),
  };

export const options = {
  scenarios: { k1: scenario },
  // 기본 요약에는 p(99)가 없다
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 목표값은 [가정]이다. 태그 조합마다 임계값을 걸어야 요약에 그 조합의 수치가 따로 나온다
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{api:seats}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{api:hold}': ['p(95)<1000', 'p(99)<2000'],
    'seat_map_duration{phase:sale}': ['p(95)<500', 'p(99)<1000'],
    'seat_map_duration{phase:soldout}': ['p(95)<500', 'p(99)<1000'],
    'hold_duration{result:created}': ['p(95)<1000', 'p(99)<2000'],
    'hold_duration{result:conflict}': ['p(95)<1000', 'p(99)<2000'],
    'hold_results{result:error}': ['count<1'],
    'dropped_iterations': ['count<1'],
  },
};

// 201·409는 정상 응답이다. 409를 실패로 세면 인기 좌석 거절이 오류율에 섞인다
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  const needed = MODEL === 'closed' ? 1 : RATE * DURATION_SECONDS;
  if (TOKEN_MODE === 'sign') {
    if (!__ENV.JWT_ACCESS_SECRET || !__ENV.ENTRY_TOKEN_SECRET) {
      fail('TOKEN_MODE=sign에는 JWT_ACCESS_SECRET과 ENTRY_TOKEN_SECRET이 필요합니다');
    }
  } else if (users.length < needed) {
    fail(`사용자가 모자랍니다: CSV ${users.length}명, 필요 ${needed}명(RATE × DURATION_SECONDS)`);
  }
  // 토큰과 회차가 맞는지 한 번 확인한다. 실패하면 측정을 시작하지 않는다
  const res = http.get(seatsUrl(), { headers: headersOf(userOf(0)), tags: { api: 'setup' } });
  if (res.status !== 200) {
    fail(`사전 확인 실패: GET seats ${res.status} ${res.body && res.body.substring(0, 200)}`);
  }
}

export default function () {
  // Note: 열린 모델에서는 반복 하나가 새로 입장한 사용자 한 명이다. 같은 사용자를 다시 쓰면 매수 한도(4매)와 입장 토큰 만료가 섞인다
  const user = userOf(exec.scenario.iterationInTest);
  const headers = headersOf(user);
  let requests = 0;

  for (let attempt = 0; attempt <= MAX_RETRY; attempt++) {
    const seatMap = http.get(seatsUrl(), { headers, tags: { api: 'seats' } });
    requests++;
    if (seatMap.status !== 200) {
      holdResults.add(1, { result: 'error' });
      break;
    }
    const body = seatMap.body;
    const soldOut = body.indexOf(AVAILABLE) < 0;
    seatMapDuration.add(seatMap.timings.duration, { phase: soldOut ? 'soldout' : 'sale' });
    if (soldOut) {
      usersFinished.add(1, { outcome: 'soldout' });
      break;
    }

    sleep(attempt === 0 ? between(THINK_MIN, THINK_MAX) : between(0.5, 1));
    const seatIds = pickSeats(body, Math.random() < 0.5 ? 1 : 2);
    if (seatIds.length === 0) {
      holdResults.add(1, { result: 'error', code: 'SEAT_PARSE_FAILED' });
      usersFinished.add(1, { outcome: 'error' });
      break;
    }
    const hold = http.post(holdUrl(), JSON.stringify({ seatIds }), {
      headers: Object.assign({ 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, headers),
      tags: { api: 'hold' },
    });
    requests++;

    const result = hold.status === 201 ? 'created' : hold.status === 409 ? 'conflict' : 'error';
    holdDuration.add(hold.timings.duration, { result });
    holdResults.add(1, { result, code: result === 'conflict' ? errorCode(hold.body) : String(hold.status) });
    check(hold, { 'hold 201 또는 409': (r) => r.status === 201 || r.status === 409 });

    if (result === 'created') {
      usersFinished.add(1, { outcome: 'held' });
      break;
    }
    if (result === 'error' || attempt === MAX_RETRY) {
      usersFinished.add(1, { outcome: result === 'error' ? 'error' : 'gave_up' });
      break;
    }
  }
  requestsPerUser.add(requests);
}

const AVAILABLE = '"available":true';

// Note: 15,000석 응답을 JSON.parse하면 부하 생성기 CPU가 먼저 포화돼 서버 병목과 섞인다.
// 문자열 검색(indexOf)으로 선택 가능한 좌석 하나를 찾고 그 좌석 객체에서 seatId만 꺼낸다.
// 좌석은 구역 → 열 → 번호 순이라 응답 앞쪽 HOT_SHARE 비율을 인기 구역으로 본다(시작 위치가 응답 길이 비율이라 근사다)
function pickSeats(body, count) {
  const hot = Math.random() < HOT_RATIO;
  const range = hot ? Math.floor(body.length * HOT_SHARE) : body.length;
  let position = findAvailable(body, Math.floor(Math.random() * range));
  const seatIds = [];
  while (position >= 0 && seatIds.length < count) {
    const seatId = seatIdAt(body, position);
    if (seatId !== null && seatIds.indexOf(seatId) < 0) {
      seatIds.push(seatId);
    }
    // 두 번째 좌석은 바로 다음 선택 가능 좌석(옆자리)을 고른다
    position = body.indexOf(AVAILABLE, position + AVAILABLE.length);
  }
  return seatIds;
}

// 시작 위치 뒤에 없으면 앞에서 다시 찾는다
function findAvailable(body, from) {
  const position = body.indexOf(AVAILABLE, from);
  return position >= 0 ? position : body.indexOf(AVAILABLE);
}

// 필드 순서에 기대지 않게 그 좌석 객체({ ... }) 안에서 seatId를 찾는다
function seatIdAt(body, position) {
  const start = body.lastIndexOf('{', position);
  const end = body.indexOf('}', position);
  const match = /"seatId":(\d+)/.exec(body.substring(start, end));
  return match ? Number(match[1]) : null;
}

function errorCode(body) {
  const match = /"code":"([A-Z_]+)"/.exec(body || '');
  return match ? match[1] : 'UNKNOWN';
}

function userOf(index) {
  if (TOKEN_MODE !== 'sign') {
    return users[index % users.length];
  }
  // Note: 사용자 ID는 실제 가입자와 겹치지 않게 USER_ID_BASE부터 쓴다. 선점 경로는 user-service를 부르지 않아 가입 행이 없어도 된다.
  // 대기열·로그인 API를 거치지 않으므로 이 모드의 수치에는 토큰 발급 부하가 없다(K1의 가정과 같다)
  const userId = USER_ID_BASE + index;
  const now = Math.floor(Date.now() / 1000);
  return {
    accessToken: signJwt({
      iss: 'iseonjwa-auth', aud: ['iseonjwa-api'], sub: String(userId), role: 'USER', token_type: 'access',
      jti: uuid(), iat: now, exp: now + 1800,
    }, __ENV.JWT_ACCESS_SECRET),
    entryToken: signJwt({
      typ: 'entry', aud: ['gateway'], sub: String(userId), scheduleId: Number(SCHEDULE_ID),
      jti: uuid(), iat: now, exp: now + 600,
    }, __ENV.ENTRY_TOKEN_SECRET),
  };
}

// HS256 JWT. 서버는 비밀값 문자열의 UTF-8 바이트를 키로 쓴다(TokenDecoders.signingKey)
function signJwt(claims, secret) {
  const input = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl')
    + '.' + encoding.b64encode(JSON.stringify(claims), 'rawurl');
  return input + '.' + crypto.hmac('sha256', secret, input, 'base64rawurl');
}

function headersOf(user) {
  return { Authorization: `Bearer ${user.accessToken}`, 'X-Entry-Token': user.entryToken };
}

function seatsUrl() {
  return `${BASE_URL}/api/v1/schedules/${SCHEDULE_ID}/seats`;
}

function holdUrl() {
  return `${BASE_URL}/api/v1/schedules/${SCHEDULE_ID}/reservations`;
}

function between(min, max) {
  return min + Math.random() * (max - min);
}

// Idempotency-Key용 UUID v4. 측정용이라 Math.random으로 충분하다
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
