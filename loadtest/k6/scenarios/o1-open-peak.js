// O1: 오픈 피크 한 흐름. 사용자 USERS명이 오픈 전 조회 → 대기열 진입 → 순번 폴링 → 입장 토큰 → 좌석 조회 → 선점 → 결제 → 확정 확인을 실제 순서대로 한다.
// 입장 토큰은 서명하지 않고 대기열 API로 받는다. 명세: docs/design/infra/open-peak-scenarios.md O1. 실행: loadtest/k6/README.md
// 사용자 행동은 두 가지 중 하나로 정한다. 기본은 모두 같은 규칙의 균일 난수이고, USERS_CSV를 주면 그 CSV 한 행이 사용자 한 명의
// 프로필이 된다(Synthetic, loadtest/synthetic/). 배포 서버에서는 SCHEDULE_ID·CONCERT_ID·OPEN_AT으로 서버에서 만든 회차를 받는다.
// 예: SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS_CSV=../data/o1-synthetic-3000.csv USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
//
// Note: 사용자 한 명이 수 분 동안 대기하므로 사용자마다 VU를 두면 VU가 수만 개 필요하다.
// VU 하나가 사용자 여러 명을 상태 기계로 들고, 다음 행동 시각이 가장 이른 사용자부터 한 걸음씩 진행한다.
// 한 VU 안의 요청은 순서대로 나가므로 좌석 조회처럼 느린 요청이 있으면 같은 VU의 다른 사용자가 늦어진다. 그 정도를 action_lateness로 남긴다
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import { numberEnv, requireEnv } from '../lib/env.js';
import { accessToken, assertSigningKeys, userIdOf, uuid } from '../lib/tokens.js';
import { authHeaders, dataOf, errorCodeOf, jsonHeaders, retryDelayMillis, url } from '../lib/http.js';
import { adminLogin, createSchedule, waitUntil } from '../lib/admin.js';
import { enterPath, positionPath } from '../lib/queue.js';
import { isSoldOut, pickSeatsFromMap, pickSeatsInZone, reservationStatus } from '../lib/booking.js';
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
const SCHEDULE_ID = __ENV.SCHEDULE_ID ? Number(__ENV.SCHEDULE_ID) : 0;
// 오픈 전 상세 새로고침을 하지 않는 사용자(진입 0번)가 오픈 뒤에도 새로고침하는 시간
const NO_ENTER_BROWSE_MS = 60000;
// 대기열 회차 캐시 갱신 주기(30초). 오픈이 이보다 가까우면 진입이 캐시를 타지 않는다
const SCHEDULE_CACHE_SECONDS = 30;

const CSV_NUMBER_COLUMNS = ['arrive_s', 'refresh_s', 'enter_delay_ms', 'enter_clicks', 'seats', 'think_ms', 'max_retry',
  'queue_patience_s', 'abandon_after_hold', 'pay_delay_s'];
const CSV_TEXT_COLUMNS = ['persona', 'zone', 'on_conflict'];
// 행동 축 구간(0 낮음, 1 중간, 2 높음). 있으면 지표 태그로만 붙이고 행동에는 쓰지 않는다. 없는 CSV(예전 파일)도 그대로 돈다
// Note: 열 이름과 계산 규칙의 정본은 loadtest/synthetic/persona_spec.py의 AXES다. 여기서는 값을 다시 계산하지 않는다
const CSV_AXIS_COLUMNS = ['ax_arrival', 'ax_entry', 'ax_demand', 'ax_speed', 'ax_persistence', 'ax_intent'];

// Note: SharedArray는 VU마다 복사하지 않고 한 번 읽은 CSV를 모든 VU가 나눠 본다. 3,000행도 VU 1,000개가 따로 들면 메모리가 낭비된다
const csvRows = __ENV.USERS_CSV ? new SharedArray('o1-users', () => parseUsersCsv(open(__ENV.USERS_CSV))) : null;

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
  // Note: 앞 N행만 쓰면 CSV가 섞여 있어도 페르소나 비율이 흔들리므로 행 수와 USERS를 정확히 맞춘다
  if (csvRows !== null && csvRows.length !== USERS) {
    throw new Error(`USERS(${USERS})는 ${__ENV.USERS_CSV}의 행 수(${csvRows.length})와 같아야 합니다`);
  }
  const schedule = SCHEDULE_ID ? scheduleFromEnv() : createSchedule(adminLogin(), { title: 'k6 O1', openInSeconds: PRE_OPEN_SECONDS });
  setApproveDelay(PG_DELAY_MS);
  console.log(`O1 회차 ${schedule.scheduleId}(공연 ${schedule.concertId}), 사용자 ${USERS}명(${csvRows === null ? '균일' : 'Synthetic ' + __ENV.USERS_CSV}), `
    + `VU ${VUS}개, 오픈 ${new Date(schedule.openAt).toISOString()}`);
  return { scheduleId: schedule.scheduleId, concertId: schedule.concertId, openAt: schedule.openAt };
}

// 서버에서 만든 회차(seed-demo.sh open "<OPEN_AT>")를 받는다. 관리자 로그인과 회차 만들기를 건너뛴다.
// Note: 오픈 시각이 너무 가까우면 회차 캐시(30초)가 갱신되기 전이라 진입이 DB를 타고, 너무 멀면 측정 시작 뒤 오픈 뒤 진행 시간(AFTER_OPEN_SECONDS)이 줄어든다
function scheduleFromEnv() {
  const concertId = Number(requireEnv('CONCERT_ID'));
  const openAt = Date.parse(requireEnv('OPEN_AT'));
  if (Number.isNaN(openAt)) {
    throw new Error(`OPEN_AT(${__ENV.OPEN_AT})을 읽을 수 없습니다. ISO-8601 UTC로 줍니다. 예: 2026-11-13T11:00:00Z`);
  }
  const untilOpen = (openAt - Date.now()) / 1000;
  if (untilOpen < SCHEDULE_CACHE_SECONDS) {
    throw new Error(`OPEN_AT이 ${untilOpen.toFixed(0)}초 뒤입니다. 회차 캐시 갱신 주기(${SCHEDULE_CACHE_SECONDS}초)보다 길게, 지금 + ${SCHEDULE_CACHE_SECONDS}초 이상이어야 합니다`);
  }
  if (untilOpen > PRE_OPEN_SECONDS + 10) {
    throw new Error(`OPEN_AT이 ${untilOpen.toFixed(0)}초 뒤입니다. PRE_OPEN_SECONDS(${PRE_OPEN_SECONDS}) + 10초 이하여야 오픈 뒤 진행 시간이 줄지 않습니다. `
      + 'PRE_OPEN_SECONDS를 OPEN_AT에 맞춰 다시 계산합니다');
  }
  return { scheduleId: SCHEDULE_ID, concertId, openAt };
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
    const profile = csvRows === null ? uniformProfile(data) : csvProfile(csvRows[first + i], data);
    list.push({
      userId,
      access: accessToken(userId),
      profile,
      state: 'browse',
      nextAt: profile.firstAt,
      enterAt: profile.enterAt,
      zone: profile.zone,
      viewed: false,
      attempt: 0,
    });
  }
  return list;
}

// 사용자 한 명의 행동 규칙. null인 칸은 그때그때 균일 난수로 정한다.
//   firstAt 첫 행동 시각, enterAt 진입(진입 0번이면 떠나는) 시각, viewFirst 진입 전에 상세를 한 번은 보는지,
//   refreshMs 상세 새로고침 간격(0이면 새로고침 없음), enterClicks 진입 요청 횟수, seats 한 번에 잡을 좌석 수,
//   zone 노리는 등급(null이면 좌석 전체에서 기존 규칙으로), thinkMs 첫 고민 시간, maxRetry 409 뒤 다시 고르는 횟수,
//   onConflict 409 뒤 행동, patienceMs 대기열에서 참는 시간(0이면 끝까지), abandonRatio 선점 뒤 이탈 확률, payDelayMs 선점부터 결제까지
function uniformProfile(data) {
  return {
    persona: 'uniform',
    axes: {},
    // 첫 행동은 흩고, 진입은 오픈 뒤 ENTER_SPREAD_SECONDS초 안에 흩는다
    firstAt: Date.now() + Math.random() * Math.min(PRE_OPEN_SECONDS, 60) * 1000,
    enterAt: data.openAt + 1000 + Math.random() * ENTER_SPREAD_SECONDS * 1000,
    viewFirst: false,
    refreshMs: null,
    enterClicks: 1,
    seats: null,
    zone: null,
    thinkMs: null,
    maxRetry: MAX_RETRY,
    onConflict: 'same_zone',
    patienceMs: 0,
    abandonRatio: ABANDON_RATIO,
    payDelayMs: null,
  };
}

function csvProfile(row, data) {
  const openAt = data.openAt;
  const arriveAt = openAt + row.arrive_s * 1000;
  // 측정 시작보다 이른 접속은 측정 시작으로 당긴다
  const firstAt = Math.max(Date.now(), arriveAt);
  const enters = row.enter_clicks > 0;
  let enterAt;
  let refreshMs;
  if (enters) {
    enterAt = row.arrive_s <= 0 ? openAt + row.enter_delay_ms : arriveAt + row.enter_delay_ms;
    // 오픈 뒤 접속자는 상세를 한 번 보고 진입한다
    refreshMs = row.arrive_s > 0 ? 0 : row.refresh_s * 1000;
  } else {
    // 진입하지 않는 사용자는 새로고침하면 오픈 뒤 NO_ENTER_BROWSE_MS까지 보다 떠나고, 새로고침하지 않으면 한 번 보고 바로 떠난다
    refreshMs = row.refresh_s * 1000;
    enterAt = refreshMs > 0 ? openAt + NO_ENTER_BROWSE_MS : firstAt;
  }
  return {
    persona: row.persona,
    axes: axisTagsOf(row),
    firstAt,
    enterAt,
    viewFirst: true,
    refreshMs,
    enterClicks: row.enter_clicks,
    seats: row.seats,
    zone: row.zone,
    thinkMs: row.think_ms,
    maxRetry: row.max_retry,
    onConflict: row.on_conflict,
    patienceMs: row.queue_patience_s * 1000,
    abandonRatio: row.abandon_after_hold,
    payDelayMs: row.pay_delay_s * 1000,
  };
}

function axisTagsOf(row) {
  const axes = {};
  for (const name of CSV_AXIS_COLUMNS) {
    if (row[name] !== undefined) {
      axes[name] = row[name];
    }
  }
  return axes;
}

// 머리말 한 줄, 쉼표 구분, 따옴표 없음(loadtest/synthetic/generate.py가 만드는 형식)
function parseUsersCsv(text) {
  const lines = text.split('\n').map((line) => line.replace(/\r$/, '')).filter((line) => line !== '');
  const header = lines[0].split(',');
  for (const name of CSV_NUMBER_COLUMNS.concat(CSV_TEXT_COLUMNS)) {
    if (header.indexOf(name) < 0) {
      throw new Error(`USERS_CSV 머리말에 ${name} 열이 없습니다(${lines[0]})`);
    }
  }
  return lines.slice(1).map((line) => {
    const cells = line.split(',');
    const row = {};
    header.forEach((name, index) => {
      row[name] = CSV_NUMBER_COLUMNS.indexOf(name) >= 0 ? Number(cells[index]) : cells[index];
    });
    return row;
  });
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
  outcomes.add(1, Object.assign({ outcome, persona: user.profile.persona }, user.profile.axes));
}

function record(user, step, res) {
  const tags = Object.assign({ step, persona: user.profile.persona }, user.profile.axes);
  if (step === 'hold') {
    // 선점에는 처음 노린 등급과 응답 코드를 붙인다. compare.py가 등급별 409 비율을 낸다(LLM 예측 "409가 VIP에 몰린다" 판정)
    // Note: 태그 이름을 status로 하면 k6 CSV의 기본 status 열과 겹친다. 그래서 code로 쓴다
    tags.zone = user.profile.zone || 'ANY';
    tags.code = String(res.status);
  }
  stepDuration.add(res.timings.duration, tags);
}

const STEPS = {
  // 1. 오픈 전 상세 새로고침(균일은 10~30초 간격, Synthetic은 프로필 간격)
  browse(user, data) {
    const profile = user.profile;
    if (Date.now() >= user.enterAt && (user.viewed || !profile.viewFirst)) {
      if (profile.enterClicks === 0) {
        return finish(user, 'browse_only');
      }
      user.state = 'enter';
      user.nextAt = Date.now();
      return;
    }
    const res = http.get(url(`/api/v1/concerts/${data.concertId}`), { tags: { api: 'concert_detail' } });
    record(user, 'browse', res);
    user.viewed = true;
    const interval = profile.refreshMs === null ? (10 + Math.random() * 20) * 1000 : profile.refreshMs;
    user.nextAt = interval > 0 ? Math.min(Date.now() + interval, user.enterAt) : user.enterAt;
  },
  // 2. 대기열 진입. 연타(enterClicks ≥ 2)는 http.batch로 한꺼번에 보내고, 마지막 성공 응답으로 순번을 읽는다.
  //    multi_device는 연타 한 번이 기기 하나다. 대기에 들어가면 본 사용자 말고 남은 기기마다 그림자 폴러를 둔다
  enter(user, data) {
    const responses = sendEnterClicks(user, data);
    let accepted = null;
    responses.forEach((res, click) => {
      record(user, click === 0 ? 'enter' : 'enter_dup', res);
      if (isQueueAccepted(res)) {
        accepted = { body: dataOf(res) };
      }
    });
    if (accepted === null) {
      return retryLater(user, retryDelayMillis(responses[0]));
    }
    user.enteredAt = Date.now();
    afterPosition(user, accepted.body);
    if (user.state === 'wait' && user.profile.persona === MULTI_DEVICE_PERSONA) {
      startShadowPollers(user, responses.slice(1));
    }
  },
  // 3. 순번 폴링
  wait(user, data) {
    // 창을 닫은 사용자. 취소 API는 부르지 않는다(서버에는 대기열에 남은 채로 보인다)
    if (leftQueue(user)) {
      return finish(user, 'left_queue');
    }
    const res = http.get(url(positionPath(data.scheduleId)), { headers: authHeaders(user.access), tags: { api: 'queue_poll' } });
    record(user, 'poll', res);
    if (res.status !== 200) {
      return retryLater(user, retryDelayMillis(res));
    }
    afterPosition(user, dataOf(res));
  },
  // 3-1. 그림자 폴링(multi_device의 다른 기기). 같은 사용자 토큰으로 순번 폴링만 한다
  shadow(shadow, data) {
    const owner = shadow.owner;
    // 본 사용자가 대기를 끝냈다(토큰 단계로 넘어감, 이탈, done). 다른 기기도 같이 닫힌 것으로 본다
    if (owner.state !== 'wait' || leftQueue(owner)) {
      shadow.state = 'done';
      return;
    }
    const res = http.get(url(positionPath(data.scheduleId)),
      { headers: authHeaders(shadow.access), tags: { api: 'queue_poll', device: 'shadow' } });
    record(shadow, 'poll_shadow', res);
    const body = res.status === 200 ? dataOf(res) : null;
    if (body && body.status !== 'WAITING') {
      // READY를 본 기기는 더 부르지 않는다. 토큰은 본 사용자만 받는다(그림자는 폴링 부하만 더한다)
      shadow.state = 'done';
      return;
    }
    shadow.nextAt = Date.now() + nextPollDelayMillis(res, body);
  },
  // 4. 입장 토큰
  token(user, data) {
    const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/queue/token`), null,
      { headers: authHeaders(user.access), tags: { api: 'queue_token' } });
    record(user, 'token', res);
    const body = dataOf(res);
    if (res.status !== 200 || !body || !body.entryToken) {
      return retryLater(user, retryDelayMillis(res));
    }
    user.entry = body.entryToken;
    queueWait.add(Date.now() - user.enteredAt);
    user.state = 'seats';
    user.nextAt = Date.now();
  },
  // 5. 좌석 조회 → 첫 고민(균일은 1~3초, 재시도는 0.5~1초)
  seats(user, data) {
    const res = http.get(url(`/api/v1/schedules/${data.scheduleId}/seats`),
      { headers: authHeaders(user.access, { 'X-Entry-Token': user.entry }), tags: { api: 'seats' }, timeout: '10s' });
    record(user, 'seats', res);
    if (res.status !== 200) {
      return finish(user, 'error');
    }
    if (isSoldOut(res.body)) {
      return finish(user, 'soldout');
    }
    user.seatIds = pickSeats(user, res.body);
    if (user.seatIds.length === 0) {
      return finish(user, 'zone_soldout');
    }
    user.state = 'hold';
    const firstThinkMs = user.profile.thinkMs === null ? (1 + Math.random() * 2) * 1000 : user.profile.thinkMs;
    user.nextAt = Date.now() + (user.attempt === 0 ? firstThinkMs : (0.5 + Math.random() * 0.5) * 1000);
  },
  // 6. 선점. 409면 좌석 조회부터 다시(프로필의 maxRetry번까지)
  hold(user, data) {
    const res = http.post(url(`/api/v1/schedules/${data.scheduleId}/reservations`), JSON.stringify({ seatIds: user.seatIds }), {
      headers: Object.assign(jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
      tags: { api: 'hold' },
    });
    record(user, 'hold', res);
    if (res.status === 201) {
      user.held = dataOf(res);
      if (Math.random() < user.profile.abandonRatio) {
        return finish(user, 'abandoned');
      }
      user.state = 'pay';
      user.nextAt = Date.now() + (user.profile.payDelayMs === null ? (5 + Math.random() * 25) * 1000 : user.profile.payDelayMs);
      return;
    }
    if (res.status === 409 && user.profile.onConflict !== 'give_up' && user.attempt < user.profile.maxRetry) {
      user.attempt++;
      if (user.profile.onConflict === 'any_zone') {
        user.zone = 'ANY';
      }
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
    record(user, 'pay', res);
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

function seatCountOf(profile) {
  return profile.seats === null ? (Math.random() < 0.5 ? 1 : 2) : profile.seats;
}

// 균일(zone이 null)은 좌석 전체에서 기존 규칙으로 고른다. Synthetic은 노리는 등급 안에서 고르고,
// 비었으면 any_zone 사용자만 아무 곳이나로 바꿔 바로 다시 고른다
function pickSeats(user, body) {
  const profile = user.profile;
  const count = seatCountOf(profile);
  if (profile.zone === null) {
    return pickSeatsFromMap(body, count);
  }
  let seatIds = pickSeatsInZone(body, count, user.zone);
  if (seatIds.length === 0 && profile.onConflict === 'any_zone' && user.zone !== 'ANY') {
    user.zone = 'ANY';
    seatIds = pickSeatsInZone(body, count, 'ANY');
  }
  return seatIds;
}

function leftQueue(user) {
  return user.profile.patienceMs > 0 && Date.now() - user.enteredAt >= user.profile.patienceMs;
}

function afterPosition(user, body) {
  if (body && body.status === 'WAITING') {
    user.state = 'wait';
    user.nextAt = Date.now() + (body.nextPollAfterMillis || 3000);
    if (user.profile.patienceMs > 0) {
      // 참는 시간이 끝나는 시각에 깨워 그때 떠나게 한다(그 뒤에는 폴링하지 않는다)
      user.nextAt = Math.min(user.nextAt, user.enteredAt + user.profile.patienceMs);
    }
  } else {
    // READY 또는 이미 토큰을 받은 상태
    user.state = 'token';
    user.nextAt = Date.now();
  }
}

// 진입 요청을 보낸다. 한 번이면 그대로, 연타면 http.batch로 동시에 보낸다. 응답은 보낸 순서(첫 요청이 본 클릭)다.
// Note: 반복문으로 이어 보내면 VU 하나에서는 앞 응답을 기다린 뒤 다음이 나간다. 실제 연타·여러 기기는 응답을 기다리지 않으므로 batch로 겹친다.
// 연타 수(CSV 최대 5)는 k6 batchPerHost 기본값(6)보다 작아 한 번에 모두 나간다
function sendEnterClicks(user, data) {
  const paramsOf = (click) => ({
    headers: authHeaders(user.access),
    tags: click === 0 ? { api: 'queue_enter' } : { api: 'queue_enter', click: 'dup' },
  });
  if (user.profile.enterClicks === 1) {
    return [http.post(url(enterPath(data.scheduleId)), null, paramsOf(0))];
  }
  const requests = [];
  for (let click = 0; click < user.profile.enterClicks; click++) {
    requests.push({ method: 'POST', url: url(enterPath(data.scheduleId)), body: null, params: paramsOf(click) });
  }
  return http.batch(requests);
}

// 진입(201·200)과 순번 조회(200)의 성공
function isQueueAccepted(res) {
  return res.status === 201 || res.status === 200;
}

// 그림자 폴러를 두는 페르소나. enter_clicks를 기기 수로 본다.
// Note: refresh_spammer의 enter_clicks는 한 기기에서 누른 횟수라 그림자를 두지 않는다(docs/ai-judgments/kangmin/2026-11-13-o1-scenario-review-four-proposals.md)
const MULTI_DEVICE_PERSONA = 'multi_device';

// 본 사용자 말고 남은 기기(enter_clicks − 1개)를 같은 VU의 상태 기계에 더한다. 첫 폴링은 그 기기가 받은 진입 응답의 간격을 따른다.
// 그림자는 결과(o1_outcomes)를 남기지 않고 step_duration{step:poll_shadow}와 http_reqs{device:shadow}로만 센다
function startShadowPollers(owner, enterResponses) {
  for (const res of enterResponses) {
    const body = isQueueAccepted(res) ? dataOf(res) : null;
    users.push({
      owner,
      profile: owner.profile,
      access: owner.access,
      state: 'shadow',
      nextAt: Date.now() + nextPollDelayMillis(res, body),
    });
  }
}

// 다음 폴링까지(ms). 성공 응답은 서버가 준 nextPollAfterMillis, 실패는 retryDelayMillis(503이면 Retry-After)
function nextPollDelayMillis(res, body) {
  if (!isQueueAccepted(res)) {
    return retryDelayMillis(res);
  }
  return (body && body.nextPollAfterMillis) || 3000;
}

function retryLater(user, millis) {
  user.nextAt = Date.now() + millis;
}

export function teardown(data) {
  setApproveDelay(0);
  console.log(`O1 대사: select status, count(*) from reservations where schedule_id = ${data.scheduleId} group by status; `
    + `select count(*) from schedule_seats where schedule_id = ${data.scheduleId} and status = 'SOLD'`);
}
