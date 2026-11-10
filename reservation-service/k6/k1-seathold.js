// 시나리오 K1: 대기열이 초당 RATE명씩 입장시킨 사용자가 좌석을 조회하고 선점한다.
// SEAT_MAP=once면 K1-H: 좌석 목록을 setup에서 한 번만 받고, 반복은 그 목록에서 골라 선점만 보낸다.
// MODEL=ramp면 스트레스: STAGES의 도착률을 STAGE_SECONDS씩 차례로 올리고 요청에 stage 태그를 붙인다.
// 설계: docs/design/reservation/k6-seathold-bottleneck-plan.md 1절(K1)·1-5절(K1-H)·1-6절(측정 종류). 실행 방법: 같은 폴더 README.md
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
// IDENTITY=direct: Gateway를 거치지 않고 reservation-service를 직접 부른다. Gateway가 검증 뒤 넣는 사용자 헤더를 k6가 넣는다.
// Note: 로그인·입장 토큰 검증과 Gateway 인증 차단기가 빠지므로, 선점 경로 자체의 한계를 볼 때만 쓴다. Gateway 경유 수치와 섞지 않는다
const DIRECT = __ENV.IDENTITY === 'direct';
// each: K1(시도마다 좌석 조회). once: K1-H(좌석 조회 병목을 떼고 선점 경로만 잰다)
const SEAT_MAP = __ENV.SEAT_MAP || 'each';
const HOLD_ONLY = SEAT_MAP === 'once';
// K2: 같은 사용자의 동시 요청(SEAT_MAP=once 전용). 반복 하나가 사용자 한 명이고, 고민 시간 뒤 선점을 한꺼번에 보낸다.
//   tabs: 탭 BURST_TABS개가 서로 다른 좌석 BURST_SEATS석씩, 서로 다른 멱등키로 동시에 선점한다(매수 한도 S1, 동시성 테스트 C4)
//   double: 같은 좌석·같은 멱등키로 BURST_REPEAT번 동시에 보낸다(연타·재전송, 멱등키 S2, 동시성 테스트 C5)
const BURST = __ENV.BURST || '';
const BURST_TABS = Number(__ENV.BURST_TABS || 4);
const BURST_SEATS = Number(__ENV.BURST_SEATS || 2);
const BURST_REPEAT = Number(__ENV.BURST_REPEAT || 3);
const MAX_PER_USER = Number(__ENV.MAX_PER_USER || 4);
// 0보다 크면 부하가 끝난 뒤 이 시간 동안 10초마다 좌석 조회 한 건을 보내 회복을 본다(B8, k6-overload-recovery.md 6절)
const RECOVERY_SECONDS = Number(__ENV.RECOVERY_SECONDS || 0);
// 탐침 사용자는 부하 사용자와 겹치지 않게 뒤쪽 ID를 쓴다
const PROBE_USER_OFFSET = 900000;
// 스트레스(MODEL=ramp): 단계마다 초당 도착 수. 단계 사이는 RAMP_UP_SECONDS 동안 올린다
const STAGES = (__ENV.STAGES || '5,10,20,40,80,160,320').split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 60);
const RAMP_UP_SECONDS = 5;
// 0보다 크면 부하 전에 WARMUP_SCHEDULE_ID 회차로 워밍업한다. 측정 회차의 좌석을 쓰지 않게 다른 회차를 쓴다(1-6절)
const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || 0);
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 10);
const WARMUP_SCHEDULE_ID = __ENV.WARMUP_SCHEDULE_ID;
const WARMUP_USER_OFFSET = 800000;
const LOAD_SECONDS = MODEL === 'ramp' ? STAGES.length * STAGE_SECONDS : DURATION_SECONDS;

if (!SCHEDULE_ID) {
  throw new Error('SCHEDULE_ID 환경변수가 필요합니다');
}
if (BURST && (!HOLD_ONLY || (BURST !== 'tabs' && BURST !== 'double'))) {
  throw new Error('BURST는 tabs 또는 double이고 SEAT_MAP=once에서만 쓴다');
}
if (WARMUP_SECONDS > 0 && (!WARMUP_SCHEDULE_ID || TOKEN_MODE !== 'sign')) {
  throw new Error('워밍업에는 WARMUP_SCHEDULE_ID와 TOKEN_MODE=sign이 필요합니다(입장 토큰이 회차마다 다르다)');
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
const probeDuration = new Trend('probe_duration', true);
// K2 사용자 결과. tabs는 over_limit(한도 초과 보유)이, double은 self_conflict(자기 요청에 409)가 결함이다
const burstUsers = new Counter('burst_users');
const burstHeldSeats = new Trend('burst_held_seats');

const vuPool = { preAllocatedVUs: Number(__ENV.PRE_VUS || 600), maxVUs: Number(__ENV.MAX_VUS || 2000) };
const scenario = MODEL === 'ramp'
  ? Object.assign({
    // 스트레스: 첫 단계는 startRate로 바로 시작하고, 다음 단계부터 RAMP_UP_SECONDS 동안 올린 뒤 유지한다
    executor: 'ramping-arrival-rate',
    startRate: STAGES[0],
    timeUnit: '1s',
    stages: STAGES.flatMap((rate, i) => (i === 0
      ? [{ target: rate, duration: `${STAGE_SECONDS}s` }]
      : [{ target: rate, duration: `${RAMP_UP_SECONDS}s` },
        { target: rate, duration: `${STAGE_SECONDS - RAMP_UP_SECONDS}s` }])),
  }, vuPool)
  : MODEL === 'closed'
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
    ...vuPool,
  };

if (WARMUP_SECONDS > 0) {
  scenario.startTime = `${WARMUP_SECONDS}s`;
}
const scenarios = BURST ? { k2: scenario } : HOLD_ONLY ? { k1h: scenario } : { k1: scenario };
if (WARMUP_SECONDS > 0) {
  // 워밍업 요청은 api:warmup 태그라 api:seats·api:hold 지표와 사용자 결과에 섞이지 않는다
  scenarios.warmup = {
    executor: 'constant-arrival-rate', exec: 'warmup', rate: WARMUP_RATE, timeUnit: '1s',
    duration: `${WARMUP_SECONDS}s`, preAllocatedVUs: 50, maxVUs: 200,
  };
}
if (RECOVERY_SECONDS > 0) {
  // Note: 부하 시나리오의 gracefulStop(30초) 동안 남은 반복과 겹치지 않게 부하가 끝나고 30초 뒤부터 보낸다
  scenarios.probe = {
    executor: 'constant-arrival-rate', exec: 'probe', rate: 1, timeUnit: '10s',
    startTime: `${WARMUP_SECONDS + LOAD_SECONDS + 30}s`, duration: `${RECOVERY_SECONDS}s`, preAllocatedVUs: 2, maxVUs: 5,
  };
}

const thresholds = {
  'http_req_failed': ['rate<0.01'],
  'http_req_duration{api:seats}': ['p(95)<500', 'p(99)<1000'],
  'http_req_duration{api:hold}': ['p(95)<1000', 'p(99)<2000'],
  'seat_map_duration{phase:sale}': ['p(95)<500', 'p(99)<1000'],
  'seat_map_duration{phase:soldout}': ['p(95)<500', 'p(99)<1000'],
  'hold_duration{result:created}': ['p(95)<1000', 'p(99)<2000'],
  'hold_duration{result:conflict}': ['p(95)<1000', 'p(99)<2000'],
  'hold_results{result:error}': ['count<1'],
  'dropped_iterations': ['count<1'],
};
if (MODEL === 'ramp') {
  // 단계마다 임계값을 걸어야 요약에 단계별 수치가 따로 나온다. 판정 기준은 1-6절(p95가 Gateway 시한 3초의 절반 이하)
  for (const rate of STAGES) {
    thresholds[`http_req_failed{stage:${rate}}`] = ['rate<0.01'];
    thresholds[`http_req_duration{api:seats,stage:${rate}}`] = ['p(95)<1500'];
    thresholds[`http_req_duration{api:hold,stage:${rate}}`] = ['p(95)<1500'];
  }
}

export const options = {
  scenarios,
  // 기본 요약에는 p(99)가 없다
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  // 목표값은 [가정]이다. 태그 조합마다 임계값을 걸어야 요약에 그 조합의 수치가 따로 나온다
  thresholds,
};

// 201·409는 정상 응답이다. 409를 실패로 세면 인기 좌석 거절이 오류율에 섞인다
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

export function setup() {
  const needed = MODEL === 'closed' ? 1
    : MODEL === 'ramp' ? STAGES.reduce((sum, rate) => sum + rate * STAGE_SECONDS, 0) : RATE * DURATION_SECONDS;
  if (DIRECT && TOKEN_MODE !== 'sign') {
    fail('IDENTITY=direct에는 TOKEN_MODE=sign이 필요합니다(사용자 ID를 만들어 쓴다)');
  }
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
  const data = HOLD_ONLY ? { seats: seatListOf(res.body) } : {};
  // 부하 시나리오가 시작하는 시각이다(워밍업이 있으면 그만큼 뒤). 측정 뒤 지표 구간을 자르는 기준이다
  console.log(`load_start epoch_ms=${Date.now() + WARMUP_SECONDS * 1000}`);
  return data;
}

export default function (data) {
  // Note: 열린 모델에서는 반복 하나가 새로 입장한 사용자 한 명이다. 같은 사용자를 다시 쓰면 매수 한도(4매)와 입장 토큰 만료가 섞인다
  const user = userOf(exec.scenario.iterationInTest);
  const headers = headersOf(user);
  if (BURST) {
    burst(data.seats, headers);
    return;
  }
  let requests = 0;
  if (MODEL === 'ramp') {
    // 이 사용자가 도착한 단계로 모든 지표에 태그를 붙인다. VU는 반복을 이어 쓰므로 반복마다 다시 정한다
    exec.vu.metrics.tags.stage = stageOf(Date.now() - exec.scenario.startTime);
  }

  for (let attempt = 0; attempt <= MAX_RETRY; attempt++) {
    const count = Math.random() < 0.5 ? 1 : 2;
    let seatIds;
    if (HOLD_ONLY) {
      sleep(attempt === 0 ? between(THINK_MIN, THINK_MAX) : between(0.5, 1));
      seatIds = pickFromList(data.seats, count);
    } else {
      seatIds = seatsFromSeatMap(attempt, headers, count);
      requests++;
      if (seatIds === null) {
        break;
      }
    }
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

// K2: 한 사용자의 선점 요청을 http.batch로 동시에 보내고 사용자 단위로 결과를 판정한다. 재시도는 하지 않는다
function burst(seats, headers) {
  sleep(between(THINK_MIN, THINK_MAX));
  const sameKey = uuid();
  const doubleSeats = pickFromList(seats, Math.random() < 0.5 ? 1 : 2);
  const requests = [];
  const used = [];
  for (let i = 0; i < (BURST === 'tabs' ? BURST_TABS : BURST_REPEAT); i++) {
    // tabs는 탭끼리 좌석이 겹치지 않게 고른다. 겹치면 매수 한도가 아니라 좌석 경쟁으로 409가 나 판정이 흐려진다
    let seatIds = doubleSeats;
    if (BURST === 'tabs') {
      for (let tries = 0; tries < 10; tries++) {
        seatIds = pickFromList(seats, BURST_SEATS);
        if (seatIds.every((id) => used.indexOf(id) < 0)) {
          break;
        }
      }
      used.push(...seatIds);
    }
    requests.push(['POST', holdUrl(), JSON.stringify({ seatIds }), {
      headers: Object.assign({ 'Content-Type': 'application/json', 'Idempotency-Key': BURST === 'tabs' ? uuid() : sameKey },
        headers),
      tags: { api: 'hold' },
    }]);
  }
  const responses = http.batch(requests);
  let held = 0;
  const created = [];
  const codes = [];
  responses.forEach((res, i) => {
    const result = res.status === 201 ? 'created' : res.status === 409 ? 'conflict' : 'error';
    const code = result === 'conflict' ? errorCode(res.body) : String(res.status);
    holdDuration.add(res.timings.duration, { result });
    holdResults.add(1, { result, code });
    codes.push(code);
    if (result === 'created') {
      held += JSON.parse(requests[i][2]).seatIds.length;
      const match = /"reservationId":(\d+)/.exec(res.body || '');
      created.push(match ? match[1] : '?');
    }
  });
  let outcome;
  if (codes.some((c) => c !== '201' && c !== 'SEAT_ALREADY_HELD' && c !== 'PURCHASE_LIMIT_EXCEEDED')) {
    outcome = 'error';
  } else if (BURST === 'tabs') {
    outcome = held > MAX_PER_USER ? 'over_limit' : held > 0 ? 'within_limit' : 'none_held';
  } else if (created.length === 0) {
    outcome = 'taken';
  } else if (created.length === responses.length) {
    // 같은 키의 응답은 모두 같은 예매여야 한다. 다르면 같은 요청이 예매 두 건을 만든 것이다
    outcome = created.every((id) => id === created[0]) ? 'same_201' : 'duplicate';
  } else {
    outcome = 'self_conflict';
  }
  burstUsers.add(1, { outcome });
  if (BURST === 'tabs') {
    burstHeldSeats.add(held);
  }
}

// 워밍업: 다른 회차로 좌석 조회와 선점을 한 번씩 보내 JIT·커넥션을 데운다. 측정 회차의 좌석은 쓰지 않는다
export function warmup() {
  const user = userOf(WARMUP_USER_OFFSET + exec.scenario.iterationInTest, WARMUP_SCHEDULE_ID);
  const headers = headersOf(user);
  const seatMap = http.get(seatsUrl(WARMUP_SCHEDULE_ID), { headers, tags: { api: 'warmup' } });
  if (seatMap.status !== 200) {
    return;
  }
  const seatIds = pickSeats(seatMap.body, 1);
  if (seatIds.length > 0) {
    http.post(holdUrl(WARMUP_SCHEDULE_ID), JSON.stringify({ seatIds }), {
      headers: Object.assign({ 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }, headers),
      tags: { api: 'warmup' },
    });
  }
}

// 단계 사이에 올리는 RAMP_UP_SECONDS는 'ramp'로 따로 둔다. 도착률이 단계 값보다 낮아 그 단계의 수치에 섞지 않는다
function stageOf(elapsedMs) {
  const elapsed = elapsedMs / 1000;
  const index = Math.min(Math.floor(elapsed / STAGE_SECONDS), STAGES.length - 1);
  if (index > 0 && elapsed - index * STAGE_SECONDS < RAMP_UP_SECONDS) {
    return 'ramp';
  }
  return String(STAGES[index]);
}

// 회복 탐침: 좌석 조회 한 건의 상태와 지연을, 부하가 끝난 뒤 몇 초째인지와 함께 남긴다
export function probe() {
  const user = userOf(PROBE_USER_OFFSET + exec.scenario.iterationInTest);
  const res = http.get(seatsUrl(), { headers: headersOf(user), tags: { api: 'probe' }, timeout: '10s' });
  probeDuration.add(res.timings.duration, { status: String(res.status) });
  // 부하가 끝난 뒤 몇 초째인지는 setup이 남긴 load_start로 측정 뒤에 계산한다(tools/b8-run.sh)
  console.log(`probe epoch_ms=${Date.now()} status=${res.status} ${Math.round(res.timings.duration)}ms`);
}

// K1: 좌석을 조회하고 고민한 뒤 고른다. 조회 실패나 매진이면 사용자 결과를 남기고 null을 돌려준다
// Note: 좌석은 고민(sleep) 전에 고른다. 고르는 기준은 이미 받은 본문이라 sleep 뒤에 골라도 결과 분포는 같다.
// 본문(830KB)을 쥔 채 1~3초 sleep하면 동시에 살아 있는 본문이 수백 개로 늘어 k6 메모리가 GB 단위로 커졌다(STEPS.md P4b 실측 3차)
function seatsFromSeatMap(attempt, headers, count) {
  const seatIds = readSeatMap(headers, count);
  if (seatIds === null) {
    return null;
  }
  sleep(attempt === 0 ? between(THINK_MIN, THINK_MAX) : between(0.5, 1));
  return seatIds;
}

// 응답 본문은 이 함수 안에서만 쓰고 고른 좌석 ID만 돌려준다
function readSeatMap(headers, count) {
  const seatMap = http.get(seatsUrl(), { headers, tags: { api: 'seats' } });
  if (seatMap.status !== 200) {
    holdResults.add(1, { result: 'error' });
    return null;
  }
  const body = seatMap.body;
  const soldOut = body.indexOf(AVAILABLE) < 0;
  seatMapDuration.add(seatMap.timings.duration, { phase: soldOut ? 'soldout' : 'sale' });
  if (soldOut) {
    usersFinished.add(1, { outcome: 'soldout' });
    return null;
  }
  return pickSeats(body, count);
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

// K1-H: setup에서 받은 응답의 선택 가능 좌석을 응답 순서(구역 → 열 → 번호)대로 모은다.
// Note: setup 반환값은 VU마다 복사된다. 15,000개 배열을 VU 2,000개에 복사하지 않게, ID가 연속이면 시작값과 개수만 넘긴다
function seatListOf(body) {
  const ids = [];
  let position = body.indexOf(AVAILABLE);
  while (position >= 0) {
    const seatId = seatIdAt(body, position);
    if (seatId !== null) {
      ids.push(seatId);
    }
    position = body.indexOf(AVAILABLE, position + AVAILABLE.length);
  }
  if (ids.length === 0) {
    fail('K1-H 준비 실패: 선택 가능한 좌석이 없습니다. 새 회차로 측정합니다');
  }
  const contiguous = ids.every((id, i) => id === ids[0] + i);
  console.log(`K1-H 좌석 목록: ${ids.length}석, ID ${contiguous ? '연속' : '비연속'}`);
  return contiguous ? { first: ids[0], count: ids.length } : { ids, count: ids.length };
}

// pickSeats와 같은 쏠림(HOT_RATIO가 앞쪽 HOT_SHARE에서)으로 고르고, 두 번째 좌석은 목록의 바로 다음(옆자리)이다.
// 목록은 측정 시작 시점에 고정이라 판매가 진행될수록 이미 잡힌 좌석을 골라 409가 K1보다 많아진다
function pickFromList(seats, count) {
  const hot = Math.random() < HOT_RATIO;
  const range = hot ? Math.max(1, Math.floor(seats.count * HOT_SHARE)) : seats.count;
  const start = Math.floor(Math.random() * range);
  const seatIds = [];
  for (let i = 0; i < count && i < seats.count; i++) {
    const index = (start + i) % seats.count;
    seatIds.push(seats.ids ? seats.ids[index] : seats.first + index);
  }
  return seatIds;
}

function errorCode(body) {
  const match = /"code":"([A-Z_]+)"/.exec(body || '');
  return match ? match[1] : 'UNKNOWN';
}

function userOf(index, scheduleId = SCHEDULE_ID) {
  if (TOKEN_MODE !== 'sign') {
    return users[index % users.length];
  }
  // Note: 사용자 ID는 실제 가입자와 겹치지 않게 USER_ID_BASE부터 쓴다. 선점 경로는 user-service를 부르지 않아 가입 행이 없어도 된다.
  // 대기열·로그인 API를 거치지 않으므로 이 모드의 수치에는 토큰 발급 부하가 없다(K1의 가정과 같다)
  const userId = USER_ID_BASE + index;
  if (DIRECT) {
    return { userId };
  }
  const now = Math.floor(Date.now() / 1000);
  return {
    accessToken: signJwt({
      iss: 'iseonjwa-auth', aud: ['iseonjwa-api'], sub: String(userId), role: 'USER', token_type: 'access',
      jti: uuid(), iat: now, exp: now + 1800,
    }, __ENV.JWT_ACCESS_SECRET),
    entryToken: signJwt({
      typ: 'entry', aud: ['gateway'], sub: String(userId), scheduleId: Number(scheduleId),
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
  if (DIRECT) {
    return { 'X-User-Id': String(user.userId), 'X-User-Role': 'USER' };
  }
  return { Authorization: `Bearer ${user.accessToken}`, 'X-Entry-Token': user.entryToken };
}

function seatsUrl(scheduleId = SCHEDULE_ID) {
  return `${BASE_URL}/api/v1/schedules/${scheduleId}/seats`;
}

function holdUrl(scheduleId = SCHEDULE_ID) {
  return `${BASE_URL}/api/v1/schedules/${scheduleId}/reservations`;
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
