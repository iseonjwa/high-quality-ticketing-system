// O1: 오픈 피크 한 흐름. 사용자 USERS명이 오픈 전 조회 → 대기열 진입 → 순번 폴링 → 입장 토큰 → 좌석 조회 → 선점 → 결제 → 확정 확인을 실제 순서대로 한다.
// 입장 토큰은 서명하지 않고 대기열 API로 받는다. 명세: docs/design/infra/open-peak-scenarios.md O1. 실행: loadtest/k6/README.md
// 사용자 행동은 두 가지 중 하나로 정한다. 기본은 모두 같은 규칙의 균일 난수이고, USERS_CSV를 주면 그 CSV 한 행이 사용자 한 명의
// 프로필이 된다(Synthetic, loadtest/synthetic/). 배포 서버에서는 SCHEDULE_ID·CONCERT_ID·OPEN_AT으로 서버에서 만든 회차를 받는다.
// 예: SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS_CSV=../data/o1-synthetic-3000.csv USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
//
// Note: 사용자 한 명이 수 분 동안 대기하므로 사용자마다 VU를 두면 VU가 수만 개 필요하다.
// VU 하나가 사용자 여러 명을 상태 기계로 들고, 다음 행동 시각이 가장 이른 사용자부터 한 걸음씩 진행한다.
// 한 VU 안의 요청은 순서대로 나가므로 좌석 조회처럼 느린 요청이 있으면 같은 VU의 다른 사용자가 늦어진다. 그 정도를 action_lateness로 남긴다
//
// SEAT_MODE(기본 map)는 좌석을 정하는 방법이다. map은 지금 동작 그대로 사용자가 좌석 조회(약 830KB)를 받아 고른다.
// partitioned는 서버가 좌석을 골라 주는 자동 배정 API가 있을 때의 상한(충돌·재조회 0)을 잰다. setup에서 좌석 조회를 한 번만 해
// 빈 좌석을 사용자마다 겹치지 않게 나눠 두고, 측정 중에는 좌석 조회 없이 바로 선점한다. 서버가 좌석을 고르는 비용은 빠진다.
// partitioned에서는 등급(zone)을 보지 않고 409가 나오면 좌석이 겹친 것이라 그 실행은 무효다. 예:
//   SEAT_MODE=partitioned SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
// auto는 자동 배정 API(POST .../reservations/auto, ISE-264)를 실제로 부른다. 좌석 조회 없이 구역·등급과 매수만 보내고 서버가 고른다.
// partitioned와 달리 서버가 고르는 비용과 구역 매진(409 ZONE_SOLD_OUT)이 들어간다. 구역을 고르는 규칙은 map과 맞춘다(autoCriteria 참고). 예:
//   SEAT_MODE=auto SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
//
// ARRIVAL_MODE(기본 profile)는 도착·진입 시각을 정하는 방법이다. profile은 지금 동작 그대로 균일 규칙 또는 CSV 행을 쓴다.
// realistic은 실제 티켓 오픈처럼 대부분이 오픈 전에 상세를 보고 기다리다 오픈 0~1초에 진입을 누르는 모습을 흉내 낸다.
// 바꾸는 것은 도착 시각, 오픈 전 새로고침 간격, 진입 시각, 진입 연타(균일만), 확정 조회 간격이다. 대기·토큰·좌석·선점·결제는 균일 또는 CSV 규칙 그대로다.
//   - 도착: 95%가 오픈 10분~1분 전에 고르게(측정 창이 짧으면 창 안에서), 5%가 오픈 +2~60초. Synthetic CSV는 83%가 오픈 전, 중앙값 5분 전이다
//   - 새로고침: 오픈까지 5분 넘게 20~40초, 1~5분 10~20초, 마지막 1분 2~5초(추정). CSV refresh_s는 3~48초, 중앙값 16초이고
//     refresh_spammer가 3~6초다. 프론트 상세 화면은 카운트다운만 돌고 자동으로 다시 부르지 않으므로(ConcertDetailPage.tsx) 이 요청은 사람이 누른 새로고침이다
//   - 진입: 오픈 전 도착자의 88%가 오픈 +0.05·+0.5·+0.9초 근처(표준편차 0.07초, 추정 반응 시간)에, 12%가 +1~30초에 누른다.
//     전체로는 약 82%가 오픈 1초 안이다(tools/arrival-preview.mjs로 계산). CSV는 오픈 1초 안 1,423명(47%)이다
//   - 연타: 균일 사용자는 55%가 한 번, 45%가 2~5번을 http.batch로 거의 동시에. CSV 사용자는 행의 enter_clicks(페르소나) 그대로
//   - 확정 조회: 프론트(paymentFlow.ts CONFIRM_INTERVAL_MILLIS·CONFIRM_LIMIT_MILLIS)와 같게 승인 직후 바로, 1초마다, 30초까지(최대 31번).
//     profile은 0.5초 뒤부터 0.5초마다 10번이라 프론트보다 2배 자주 묻는다
// Note: 한 VU의 사용자는 차례로 요청하므로 오픈 1초 안 몰림을 살리려면 VU당 사용자를 적게 둔다. action_lateness{state:enter}로 밀린 정도를 본다. 예:
//   ARRIVAL_MODE=realistic SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
//
// v2 CSV(loadtest/synthetic/persona_spec_v2.py, ISE-289)는 열 4개가 더 있다. 이 열이 있을 때만 아래 행동을 하고, 없으면 지금과 같다.
//   - cancel_after_confirm_s > 0: 확정하고 그 초 뒤 처음 확정한 예매를 취소한다(o1_outcomes canceled·cancel_failed)
//   - rebook_seats > 0: 확정 뒤 같은 회차에 대기열 진입부터 다시 서서 그 매수를 더 산다(rebook_confirmed, 한도 초과 rebook_limit 등)
//   - after_soldout: 좌석 조회에서 매진을 본 뒤 leave 바로 나감(soldout), refresh 좌석 조회를 SOLDOUT_REFRESH_SECONDS(기본 30)초마다
//     SOLDOUT_REFRESH_COUNT(기본 10)번 다시 불러 풀린 좌석을 찾음(soldout_refresh), stay 같은 간격·횟수로 공연 상세만 새로고침(soldout_stay)
//   - sessions = 2: 첫 선점을 같은 계정의 두 번째 세션과 동시에 보낸다(session2_*)
// 확정 뒤·매진 뒤 결과(soldout_seen·resale_found·canceled·rebook_*·session2_*)는 사용자 한 명의 최종 결과가 아니라 덧붙은 결과다.
// compare.py가 이 값들을 3절 결과표에서 빼고 12절에 따로 센다. v2는 partitioned를 지원하지 않는다(나눠 둔 좌석 밖을 다시 사야 한다). 예:
//   SCHEDULE_ID=<id> CONCERT_ID=<id> OPEN_AT=<ISO-8601 UTC> USERS_CSV=../data/o1-synthetic-v2-3000.csv USERS=3000 VUS=300 bash loadtest/k6/run.sh scenarios/o1-open-peak.js
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
import {
  availableSeatIds, cancelReservation, isSoldOut, pickSeatsFromMap, pickSeatsInZone, reservationStatus, userOf,
} from '../lib/booking.js';
import { setApproveDelay } from '../lib/mockpg.js';
import { realisticArrival, realisticClicks, realisticRefreshMillis } from '../lib/arrival.js';

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
const SEAT_MODE = __ENV.SEAT_MODE || 'map';
if (SEAT_MODE !== 'map' && SEAT_MODE !== 'partitioned' && SEAT_MODE !== 'auto') {
  throw new Error(`SEAT_MODE(${SEAT_MODE})는 map, partitioned, auto 중 하나여야 합니다`);
}
const ARRIVAL_MODE = __ENV.ARRIVAL_MODE || 'profile';
if (ARRIVAL_MODE !== 'profile' && ARRIVAL_MODE !== 'realistic') {
  throw new Error(`ARRIVAL_MODE(${ARRIVAL_MODE})는 profile 또는 realistic이어야 합니다`);
}
const REALISTIC = ARRIVAL_MODE === 'realistic';
// 결제 승인 뒤 확정 조회. profile은 0.5초 뒤부터 0.5초마다 10번, realistic은 프론트와 같게 바로부터 1초마다 31번(30초)
const CONFIRM_FIRST_DELAY_MS = REALISTIC ? 0 : 500;
const CONFIRM_INTERVAL_MS = REALISTIC ? 1000 : 500;
const CONFIRM_MAX_POLLS = REALISTIC ? 31 : 10;
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
// v2 열(정본 loadtest/synthetic/persona_spec_v2.py의 NEW_FIELDS). 넷 다 있거나 넷 다 없어야 한다
const CSV_V2_NUMBER_COLUMNS = ['cancel_after_confirm_s', 'rebook_seats', 'sessions'];
const CSV_V2_TEXT_COLUMNS = ['after_soldout'];
// 매진 뒤 refresh·stay의 간격(초)과 횟수. 초안의 "매진 뒤에도 좌석 화면을 30초마다 새로고침"(가정)을 기본으로 둔다
const SOLDOUT_REFRESH_SECONDS = numberEnv('SOLDOUT_REFRESH_SECONDS', 30);
const SOLDOUT_REFRESH_COUNT = numberEnv('SOLDOUT_REFRESH_COUNT', 10);

// Note: SharedArray는 VU마다 복사하지 않고 한 번 읽은 CSV를 모든 VU가 나눠 본다. 3,000행도 VU 1,000개가 따로 들면 메모리가 낭비된다
const csvRows = __ENV.USERS_CSV ? new SharedArray('o1-users', () => parseUsersCsv(open(__ENV.USERS_CSV))) : null;
// v2 열이 있는 CSV면 확정 뒤·매진 뒤 행동을 켠다. 균일 모드와 v1 CSV는 false라 새 분기를 하나도 타지 않는다
const V2 = csvRows !== null && csvRows.length > 0 && csvRows[0].sessions !== undefined;

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
  if (V2 && SEAT_MODE === 'partitioned') {
    throw new Error('v2 CSV(추가 예매·두 세션)는 SEAT_MODE=partitioned를 지원하지 않습니다. map 또는 auto로 실행합니다');
  }
  const schedule = SCHEDULE_ID ? scheduleFromEnv() : createSchedule(adminLogin(), { title: 'k6 O1', openInSeconds: PRE_OPEN_SECONDS });
  setApproveDelay(PG_DELAY_MS);
  console.log(`O1 회차 ${schedule.scheduleId}(공연 ${schedule.concertId}), 사용자 ${USERS}명(${csvRows === null ? '균일' : 'Synthetic ' + __ENV.USERS_CSV}), `
    + `VU ${VUS}개, 오픈 ${new Date(schedule.openAt).toISOString()}, 좌석 ${SEAT_MODE}, 도착 ${ARRIVAL_MODE}`
    + (V2 ? `, v2 행동(매진 뒤 ${SOLDOUT_REFRESH_SECONDS}초 × ${SOLDOUT_REFRESH_COUNT}번)` : ''));
  const data = { scheduleId: schedule.scheduleId, concertId: schedule.concertId, openAt: schedule.openAt };
  if (SEAT_MODE === 'partitioned') {
    Object.assign(data, partitionSeats(schedule.scheduleId));
  }
  return data;
}

// partitioned: 사용자 번호 i에게 seatIds[i × perUser]부터 perUser석을 준다. perUser는 한 사용자가 잡을 수 있는 최대 좌석 수다.
// Note: 좌석 조회는 여기서 한 번뿐이고 측정 구간(오픈 전 조회부터) 밖이다. 서명한 입장 토큰으로 부르는 것은 A0와 같다
function partitionSeats(scheduleId) {
  const perUser = csvRows === null ? 2 : csvRows.reduce((max, row) => Math.max(max, row.seats), 1);
  const seatIds = availableSeatIds(scheduleId, userOf(userIdOf(0) - 1, scheduleId));
  const needed = USERS * perUser;
  if (seatIds.length < needed) {
    throw new Error(`SEAT_MODE=partitioned: 빈 좌석 ${seatIds.length}석이 필요한 ${needed}석(USERS ${USERS} × 사용자당 ${perUser}석)보다 적습니다`);
  }
  console.log(`좌석 나눔: 빈 좌석 ${seatIds.length}석 중 ${needed}석을 사용자마다 ${perUser}석씩`);
  return { seatIds: seatIds.slice(0, needed), seatsPerUser: perUser };
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
    if (REALISTIC) {
      applyRealisticArrival(profile, data);
    }
    list.push({
      index: first + i,
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
//   onConflict 409 뒤 행동, patienceMs 대기열에서 참는 시간(0이면 끝까지), abandonRatio 선점 뒤 이탈 확률, payDelayMs 선점부터 결제까지.
//   v2만 쓰는 칸(V2가 false면 읽지 않는다): cancelAfterMs 확정 뒤 취소까지(0 취소 안 함), rebookSeats 추가 예매 매수(0 안 함),
//   afterSoldout 매진 뒤 행동(leave·refresh·stay), sessions 동시에 선점하는 세션 수
const NO_V2_BEHAVIOR = { cancelAfterMs: 0, rebookSeats: 0, afterSoldout: 'leave', sessions: 1 };

function v2BehaviorOf(row) {
  if (!V2) {
    return NO_V2_BEHAVIOR;
  }
  return {
    cancelAfterMs: row.cancel_after_confirm_s * 1000,
    rebookSeats: row.rebook_seats,
    afterSoldout: row.after_soldout,
    sessions: row.sessions,
  };
}

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
    ...NO_V2_BEHAVIOR,
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
  return Object.assign({
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
  }, v2BehaviorOf(row));
}

// realistic: 도착·진입·새로고침만 바꾸고 좌석·고민·재시도·대기 인내·이탈·결제 지연은 균일 또는 CSV 값을 둔다.
// CSV 사용자의 연타 수(enter_clicks)는 페르소나가 정한 값이라 그대로 두고, 진입 0번(browse_only)은 오픈 뒤 NO_ENTER_BROWSE_MS까지 보다 떠난다
function applyRealisticArrival(profile, data) {
  const arrival = realisticArrival(data.openAt, Date.now(), Math.random);
  profile.firstAt = arrival.firstAt;
  profile.enterAt = arrival.enterAt;
  profile.viewFirst = true;
  // 오픈 뒤 도착자는 상세를 한 번 보고 진입한다. 오픈 전 도착자는 남은 시간에 따라 간격이 줄어든다(browse 단계)
  profile.refreshMs = arrival.late ? 0 : null;
  profile.refreshRamp = !arrival.late;
  if (csvRows === null) {
    profile.enterClicks = realisticClicks(Math.random);
  }
  if (profile.enterClicks === 0) {
    profile.enterAt = Math.max(arrival.firstAt, data.openAt + NO_ENTER_BROWSE_MS);
  }
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
  const v2Columns = CSV_V2_NUMBER_COLUMNS.concat(CSV_V2_TEXT_COLUMNS);
  const v2Present = v2Columns.filter((name) => header.indexOf(name) >= 0);
  if (v2Present.length > 0 && v2Present.length < v2Columns.length) {
    throw new Error(`USERS_CSV의 v2 열은 ${v2Columns.join(', ')}가 모두 있어야 합니다(있는 열 ${v2Present.join(', ')})`);
  }
  const numberColumns = CSV_NUMBER_COLUMNS.concat(CSV_V2_NUMBER_COLUMNS);
  return lines.slice(1).map((line) => {
    const cells = line.split(',');
    const row = {};
    header.forEach((name, index) => {
      row[name] = numberColumns.indexOf(name) >= 0 ? Number(cells[index]) : cells[index];
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
  if (user.rebookRound) {
    return finishRebook(user, outcome);
  }
  user.state = 'done';
  outcomes.add(1, Object.assign({ outcome, persona: user.profile.persona }, user.profile.axes));
  if (V2 && outcome === 'confirmed') {
    planAfterConfirm(user);
  }
}

// v2의 덧붙은 결과. 사용자 한 명의 최종 결과(finish)와 같은 지표에 다른 outcome 이름으로 남긴다(compare.py FOLLOW_UP_OUTCOMES)
function followUp(user, outcome) {
  outcomes.add(1, Object.assign({ outcome, persona: user.profile.persona }, user.profile.axes));
}

// v2: 확정 뒤 할 일을 차례로 정한다. 추가 예매가 먼저, 취소가 다음이다. 할 일이 없으면 done이다.
// Note: 취소 시각은 처음 확정한 시각부터 잰다. 추가 예매가 길어져 그 시각이 지났으면 추가 예매가 끝난 즉시 취소한다
function planAfterConfirm(user) {
  const profile = user.profile;
  if (user.firstReservationId === undefined) {
    user.firstReservationId = user.held.reservationId;
    user.confirmedAt = Date.now();
  }
  if (profile.rebookSeats > 0 && !user.rebookDone) {
    return startRebook(user);
  }
  if (profile.cancelAfterMs > 0 && !user.cancelDone) {
    user.state = 'cancel';
    user.nextAt = Math.max(Date.now(), user.confirmedAt + profile.cancelAfterMs);
    return;
  }
  user.state = 'done';
}

// 추가 예매는 대기열 진입부터 다시 한다.
// Note: 선점이 성공하면 입장 토큰을 소모하고 대기열 issued에서 지운다(ADR 0053 결정 3, ConsumedEntryTokens). 그래서 다시 진입하면
// enter.lua가 새 순번을 줘 줄 맨 뒤에 선다. 같은 토큰으로 바로 선점하면 403 ENTRY_TOKEN_USED라 토큰도 새로 받는다.
// 고민 시간은 첫 예매의 think_ms를 다시 쓴다
function startRebook(user) {
  user.rebookDone = true;
  user.rebookRound = true;
  user.attempt = 0;
  user.zone = user.profile.zone;
  user.state = 'enter';
  user.nextAt = Date.now() + user.profile.thinkMs;
}

// 추가 예매의 끝. 결과 앞에 rebook_를 붙여 덧붙은 결과로 남긴다(confirmed → rebook_confirmed, limit → rebook_limit)
function finishRebook(user, outcome) {
  user.rebookRound = false;
  followUp(user, `rebook_${outcome}`);
  planAfterConfirm(user);
}

// 좌석 조회에서 매진을 봤다. 추가 예매 중이거나 leave면 지금처럼 끝낸다
function afterSoldOut(user) {
  const action = user.rebookRound ? 'leave' : user.profile.afterSoldout;
  if (!V2 || action === 'leave') {
    return finish(user, 'soldout');
  }
  followUp(user, 'soldout_seen');
  user.soldoutChecks = 0;
  user.state = action === 'refresh' ? 'soldoutRefresh' : 'soldoutStay';
  user.nextAt = Date.now() + SOLDOUT_REFRESH_SECONDS * 1000;
}

// 같은 계정의 두 번째 세션이 같이 선점할지. 첫 예매의 첫 선점에서 한 번만 한다(재시도·추가 예매·매진 뒤 재조회는 한 세션)
function wantsSecondSession(user) {
  return V2 && user.profile.sessions === 2 && user.attempt === 0 && !user.rebookRound && !user.secondTried;
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
    const interval = profile.refreshRamp ? realisticRefreshMillis(data.openAt - Date.now(), Math.random)
      : profile.refreshMs === null ? (10 + Math.random() * 20) * 1000 : profile.refreshMs;
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
    if (SEAT_MODE === 'partitioned') {
      return takePartitionedSeats(user, data);
    }
    if (SEAT_MODE === 'auto') {
      return chooseAutoCriteria(user);
    }
    const res = http.get(url(`/api/v1/schedules/${data.scheduleId}/seats`),
      { headers: authHeaders(user.access, { 'X-Entry-Token': user.entry }), tags: { api: 'seats' }, timeout: '10s' });
    record(user, 'seats', res);
    if (res.status !== 200) {
      return finish(user, 'error');
    }
    if (isSoldOut(res.body)) {
      return afterSoldOut(user);
    }
    user.seatIds = pickSeats(user, res.body);
    if (user.seatIds.length === 0) {
      return finish(user, 'zone_soldout');
    }
    if (wantsSecondSession(user)) {
      // 두 번째 세션도 같은 좌석 화면에서 따로 고른다. 겹치면 그대로 보낸다(실제로도 두 기기가 같은 좌석을 누를 수 있다)
      user.secondBody = { seatIds: pickSeats(user, res.body) };
    }
    user.state = 'hold';
    const firstThinkMs = user.profile.thinkMs === null ? (1 + Math.random() * 2) * 1000 : user.profile.thinkMs;
    user.nextAt = Date.now() + (user.attempt === 0 ? firstThinkMs : (0.5 + Math.random() * 0.5) * 1000);
  },
  // 6. 선점. 409면 좌석 조회부터 다시(프로필의 maxRetry번까지). auto는 좌석 대신 구역·등급과 매수를 보낸다
  hold(user, data) {
    const path = SEAT_MODE === 'auto' ? 'reservations/auto' : 'reservations';
    const body = SEAT_MODE === 'auto' ? user.autoBody : { seatIds: user.seatIds };
    const params = {
      headers: Object.assign(jsonHeaders(user.access, { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
      tags: { api: 'hold' },
    };
    const res = user.secondBody
      ? holdFromTwoSessions(user, data, path, body, params)
      : http.post(url(`/api/v1/schedules/${data.scheduleId}/${path}`), JSON.stringify(body), params);
    record(user, 'hold', res);
    if (V2 && res.status !== 201) {
      // 재시도해도 같은 이유로 거절되는 두 가지는 좌석을 다시 고르지 않고 끝낸다(추가 예매에서는 rebook_limit·rebook_token_used)
      const code = errorCodeOf(res);
      if (code === 'PURCHASE_LIMIT_EXCEEDED') {
        return finish(user, 'limit');
      }
      if (code === 'ENTRY_TOKEN_USED') {
        return finish(user, 'token_used');
      }
    }
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
  // Note: 배포 결제 서버는 기본 PG가 토스라 pgProvider가 없으면 결제창 값(clientPaymentRef)을 요구해 400이 난다.
  // MOCK을 명시하면 Mock 건너뛰기(PAYMENT_PG_MOCK_BYPASS)로 승인되고, local(Mock 기본)에서도 그대로 MOCK이다.
  pay(user) {
    const res = http.post(url('/api/v1/payments'),
      JSON.stringify({ paymentToken: user.held.paymentToken, method: 'CARD', amount: user.held.totalAmount, pgProvider: 'MOCK' }), {
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
    user.nextAt = Date.now() + CONFIRM_FIRST_DELAY_MS;
  },
  // 8. 확정 확인(profile 0.5초 간격 최대 10번, realistic 1초 간격 최대 31번)
  confirm(user) {
    const status = reservationStatus(user, user.held.reservationId);
    if (status === 'CONFIRMED') {
      confirmLatency.add(Date.now() - user.approvedAt);
      return finish(user, 'confirmed');
    }
    if (++user.polls >= CONFIRM_MAX_POLLS) {
      return finish(user, 'confirm_timeout');
    }
    user.nextAt = Date.now() + CONFIRM_INTERVAL_MS;
  },
  // 9. 확정 뒤 취소(v2 cancel_after_confirm_s). 처음 확정한 예매를 취소한다
  cancel(user) {
    const res = cancelReservation(user, user.firstReservationId);
    record(user, 'cancel', res);
    user.cancelDone = true;
    followUp(user, res.status === 202 ? 'canceled' : 'cancel_failed');
    planAfterConfirm(user);
  },
  // 10. 매진 뒤 좌석 조회 반복(v2 after_soldout=refresh, 취소표 노리기). 빈 좌석이 보이면 선점으로 간다
  // Note: 입장 토큰은 선점이 성공해야 소모하므로 매진을 본 사람의 토큰은 만료(10분)까지 좌석 조회에 쓸 수 있다(ADR 0053 결과).
  // 기본 30초 × 10번이면 300초라 그 안이다. 조회가 200이 아니면(만료 403 등) 더 하지 않고 끝낸다
  soldoutRefresh(user, data) {
    const res = http.get(url(`/api/v1/schedules/${data.scheduleId}/seats`), {
      headers: authHeaders(user.access, { 'X-Entry-Token': user.entry }),
      tags: { api: 'seats', after: 'soldout' },
      timeout: '10s',
    });
    record(user, 'seats_refresh', res);
    user.soldoutChecks++;
    if (res.status === 200 && !isSoldOut(res.body)) {
      const seatIds = pickSeats(user, res.body);
      if (seatIds.length > 0) {
        followUp(user, 'resale_found');
        user.seatIds = seatIds;
        user.state = 'hold';
        user.nextAt = Date.now() + (0.5 + Math.random() * 0.5) * 1000;
        return;
      }
    }
    if (res.status !== 200 || user.soldoutChecks >= SOLDOUT_REFRESH_COUNT) {
      return finish(user, 'soldout_refresh');
    }
    user.nextAt = Date.now() + SOLDOUT_REFRESH_SECONDS * 1000;
  },
  // 11. 매진 화면을 띄워 둔 채 공연 상세만 새로고침한다(v2 after_soldout=stay). 좌석 조회보다 가벼운 요청이라 refresh와 비교한다
  soldoutStay(user, data) {
    const res = http.get(url(`/api/v1/concerts/${data.concertId}`), { tags: { api: 'concert_detail', after: 'soldout' } });
    record(user, 'browse_soldout', res);
    if (++user.soldoutChecks >= SOLDOUT_REFRESH_COUNT) {
      return finish(user, 'soldout_stay');
    }
    user.nextAt = Date.now() + SOLDOUT_REFRESH_SECONDS * 1000;
  },
};

// v2 sessions=2: 같은 계정의 두 번째 세션이 같은 입장 토큰으로 첫 세션과 같은 순간 선점한다. 응답은 결제를 이어 갈 쪽 하나다.
// Note: 두 기기는 같은 순번에서 같은 입장 토큰을 받는다(issue-token.lua가 유효한 issued를 그대로 돌려준다). 그래서 토큰 하나를 같이 쓴다.
// 입장 토큰 1회 사용(ADR 0053)이 지켜지면 한쪽만 201이고, 다른 쪽은 403 ENTRY_TOKEN_USED나 좌석·매수 409다.
// 둘 다 201이면 session2_double_held로 따로 세어 정합성 문제로 본다
function holdFromTwoSessions(user, data, path, body, params) {
  const secondBody = user.secondBody;
  user.secondBody = null;
  user.secondTried = true;
  const target = url(`/api/v1/schedules/${data.scheduleId}/${path}`);
  const secondParams = {
    headers: Object.assign(jsonHeaders(accessToken(user.userId), { 'Idempotency-Key': uuid() }), { 'X-Entry-Token': user.entry }),
    tags: { api: 'hold', session: 'second' },
  };
  const [first, second] = http.batch([
    { method: 'POST', url: target, body: JSON.stringify(body), params },
    { method: 'POST', url: target, body: JSON.stringify(secondBody), params: secondParams },
  ]);
  record(user, 'hold_second', second);
  followUp(user, secondSessionOutcome(first, second));
  // 첫 세션이 실패하고 두 번째가 성공했으면 두 번째로 결제를 이어 간다. 둘 다 실패면 첫 세션 응답으로 재시도·포기를 정한다
  return first.status !== 201 && second.status === 201 ? second : first;
}

function secondSessionOutcome(first, second) {
  if (second.status === 201) {
    return first.status === 201 ? 'session2_double_held' : 'session2_held';
  }
  const code = errorCodeOf(second);
  if (code === 'ENTRY_TOKEN_USED') {
    return 'session2_token_used';
  }
  if (code === 'PURCHASE_LIMIT_EXCEEDED') {
    return 'session2_limit';
  }
  return second.status === 409 ? 'session2_conflict' : 'session2_other';
}

// partitioned의 5단계. 좌석 조회 없이 setup에서 나눠 둔 좌석을 쓰고, 고민 시간은 map과 같다
function takePartitionedSeats(user, data) {
  const base = user.index * data.seatsPerUser;
  user.seatIds = data.seatIds.slice(base, base + seatCountOf(user.profile));
  user.state = 'hold';
  const firstThinkMs = user.profile.thinkMs === null ? (1 + Math.random() * 2) * 1000 : user.profile.thinkMs;
  user.nextAt = Date.now() + (user.attempt === 0 ? firstThinkMs : (0.5 + Math.random() * 0.5) * 1000);
}

// auto의 5단계. 좌석 조회 없이 본문(구역·등급, 매수)만 정하고, 고민 시간은 map과 같다.
// Note: 비교 공정성. map 균일 규칙은 70%가 좌석 조회 본문 앞 10%(약 1,500석 = 구역 A·B와 C 절반, 구역당 600석)에서,
// 30%가 전체에서 고른다. auto 균일은 같은 비율로 70%가 구역 A~C 중 하나, 30%가 구역 A~Y 중 하나를 무작위로 고른다(앞 구역 쏠림 유지,
// 인기 범위는 1,800석으로 map보다 조금 넓다). Synthetic은 CSV zone(VIP·R·S)을 등급으로 보내고, ANY는 회차 전체(본문에 범위 없음)다.
// 409 뒤에는 map처럼 다시 고른다. 균일은 같은 규칙으로 새 구역을, any_zone 사용자는 ANY(회차 전체)로 바꿔 보낸다
function chooseAutoCriteria(user) {
  const count = seatCountFor(user);
  if (user.profile.zone === null) {
    const hot = Math.random() < 0.7;
    const index = Math.floor(Math.random() * (hot ? AUTO_HOT_SECTIONS : AUTO_SECTIONS));
    user.autoBody = { section: String.fromCharCode(65 + index), quantity: count };
  } else {
    user.autoBody = user.zone === 'ANY' ? { quantity: count } : { grade: user.zone, quantity: count };
  }
  if (wantsSecondSession(user)) {
    // 자동 배정은 서버가 좌석을 고르므로 두 번째 세션도 같은 조건을 보낸다
    user.secondBody = user.autoBody;
  }
  user.state = 'hold';
  const firstThinkMs = user.profile.thinkMs === null ? (1 + Math.random() * 2) * 1000 : user.profile.thinkMs;
  user.nextAt = Date.now() + (user.attempt === 0 ? firstThinkMs : (0.5 + Math.random() * 0.5) * 1000);
}

// 공연장 시드(V20261030_2__venue_seed.sql)는 구역 A~Y 25개, 구역당 600석이다
const AUTO_SECTIONS = 25;
const AUTO_HOT_SECTIONS = 3;

function seatCountOf(profile) {
  return profile.seats === null ? (Math.random() < 0.5 ? 1 : 2) : profile.seats;
}

// 추가 예매(v2) 중이면 rebook_seats, 아니면 첫 예매 매수다
function seatCountFor(user) {
  return user.rebookRound ? user.profile.rebookSeats : seatCountOf(user.profile);
}

// 균일(zone이 null)은 좌석 전체에서 기존 규칙으로 고른다. Synthetic은 노리는 등급 안에서 고르고,
// 비었으면 any_zone 사용자만 아무 곳이나로 바꿔 바로 다시 고른다
function pickSeats(user, body) {
  const profile = user.profile;
  const count = seatCountFor(user);
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
