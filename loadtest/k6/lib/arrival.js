// O1 ARRIVAL_MODE=realistic의 도착·진입·새로고침 규칙. k6 모듈을 쓰지 않아 node로도 분포를 계산할 수 있다(tools/arrival-preview.mjs).
// 모든 값은 추정이다. 근거와 고른 이유는 scenarios/o1-open-peak.js 머리말과 loadtest/k6/README.md에 있다.
// random은 [0, 1) 난수 함수다. k6에서는 Math.random을 넘기고, 미리보기는 시드 있는 난수를 넘긴다.

// 오픈 뒤에 처음 들어오는 사람의 비율. 이들은 오픈 +2~60초에 와서 상세를 한 번 보고 1~3초 뒤 진입한다
export const LATE_ARRIVAL_RATIO = 0.05;
// 오픈 전에 와 있던 사람의 진입 반응. 오픈 +0, +0.5, +0.9초 근처 세 봉우리(표준편차 0.07초)와, 1~30초 늦게 누르는 사람
export const CLICK_PEAKS = [
  { atSeconds: 0.05, weight: 0.35 },
  { atSeconds: 0.5, weight: 0.33 },
  { atSeconds: 0.9, weight: 0.2 },
];
export const CLICK_PEAK_SD_SECONDS = 0.07;
// 봉우리 무게 합(0.88)을 뺀 나머지 0.12가 늦게 누르는 사람이다
export const DELAYED_MAX_SECONDS = 30;
// 진입 요청 횟수. 55%는 한 번, 45%는 2~5번 연타(광클). Synthetic CSV는 한 번 56%다
export const SINGLE_CLICK_RATIO = 0.55;
export const MAX_CLICKS = 5;
// 오픈 전 도착 구간(초 전). 오픈 10분~1분 전에 고르게. 측정 창이 짧으면 창 안에서 고르게
export const ARRIVE_EARLIEST_SECONDS = 600;
export const ARRIVE_LATEST_SECONDS = 60;

// 사용자 한 명의 첫 접속 시각과 진입 시각(ms). now보다 이른 접속은 없다
export function realisticArrival(openAt, now, random) {
  if (random() < LATE_ARRIVAL_RATIO) {
    const arriveAt = openAt + (2 + 58 * random() ** 2) * 1000;
    return { late: true, firstAt: arriveAt, enterAt: arriveAt + (1 + 2 * random()) * 1000 };
  }
  const untilOpen = Math.max(0, (openAt - now) / 1000);
  const earliest = Math.min(ARRIVE_EARLIEST_SECONDS, untilOpen);
  const latest = Math.min(ARRIVE_LATEST_SECONDS, earliest);
  const firstAt = openAt - (latest + random() * (earliest - latest)) * 1000;
  return { late: false, firstAt, enterAt: openAt + clickDelayMillis(random) };
}

// 오픈부터 진입 요청까지(ms)
export function clickDelayMillis(random) {
  let pick = random();
  for (const peak of CLICK_PEAKS) {
    if (pick < peak.weight) {
      return Math.abs(peak.atSeconds + CLICK_PEAK_SD_SECONDS * normal(random)) * 1000;
    }
    pick -= peak.weight;
  }
  // 늦게 누르는 사람은 1초 뒤부터, 앞쪽(몇 초)에 많고 수십 초까지 드물게
  return (1 + (DELAYED_MAX_SECONDS - 1) * random() ** 2) * 1000;
}

export function realisticClicks(random) {
  if (random() < SINGLE_CLICK_RATIO) {
    return 1;
  }
  return 2 + Math.floor(random() * (MAX_CLICKS - 1));
}

// 오픈까지 남은 시간에 따른 상세 새로고침 간격(ms). 5분 넘게 남으면 20~40초, 1~5분은 10~20초, 마지막 1분과 오픈 뒤는 2~5초
export function realisticRefreshMillis(untilOpenMillis, random) {
  if (untilOpenMillis > 300000) {
    return (20 + 20 * random()) * 1000;
  }
  if (untilOpenMillis > 60000) {
    return (10 + 10 * random()) * 1000;
  }
  return (2 + 3 * random()) * 1000;
}

// 표준정규 난수(Box-Muller)
function normal(random) {
  const u = 1 - random();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random());
}
