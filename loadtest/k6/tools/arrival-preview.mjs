// O1 도착 규칙 미리보기. 서버 없이 사용자 USERS명의 진입 시각·연타·오픈 전 상세 새로고침 수를 계산해 profile(균일)과 realistic을 비교한다.
// 사용: node loadtest/k6/tools/arrival-preview.mjs [USERS, 기본 3000] [오픈 전 초, 기본 600] [시드, 기본 20261115]
// Note: 예정 시각만 센다. 실제 실행에서는 한 VU의 사용자가 차례로 요청해 늦어진다(action_lateness). 균일 연타는 1번 고정이다
import { realisticArrival, realisticClicks, realisticRefreshMillis } from '../lib/arrival.js';

const users = Number(process.argv[2] || 3000);
const preOpenSeconds = Number(process.argv[3] || 600);
const random = mulberry32(Number(process.argv[4] || 20261115));
const now = 0;
const openAt = preOpenSeconds * 1000;

const modes = {
  profile: () => ({
    firstAt: now + random() * Math.min(preOpenSeconds, 60) * 1000,
    enterAt: openAt + 1000 + random() * 10 * 1000,
    clicks: 1,
    refresh: () => (10 + random() * 20) * 1000,
  }),
  realistic: () => {
    const arrival = realisticArrival(openAt, now, random);
    return {
      firstAt: arrival.firstAt,
      enterAt: arrival.enterAt,
      clicks: realisticClicks(random),
      refresh: arrival.late ? () => 0 : (t) => realisticRefreshMillis(openAt - t, random),
    };
  },
};

for (const [name, make] of Object.entries(modes)) {
  const enters = [];
  const details = [];
  let enterRequests = 0;
  let enterRequestsIn1s = 0;
  for (let i = 0; i < users; i++) {
    const user = make();
    // browse 단계와 같은 순서: 첫 접속에 한 번 보고, 간격마다 보다가 진입 시각이 되면 진입한다
    let t = user.firstAt;
    details.push(t);
    for (;;) {
      const interval = user.refresh(t);
      t = interval > 0 ? Math.min(t + interval, user.enterAt) : user.enterAt;
      if (t >= user.enterAt) {
        break;
      }
      details.push(t);
    }
    const delay = Math.max(user.enterAt, user.firstAt) - openAt;
    enters.push(delay);
    enterRequests += user.clicks;
    if (delay < 1000) {
      enterRequestsIn1s += user.clicks;
    }
  }
  const within = (ms) => enters.filter((d) => d >= 0 && d < ms).length;
  const preOpen = details.filter((t) => t < openAt).length;
  const lastMinute = details.filter((t) => t >= openAt - 60000 && t < openAt).length;
  console.log(`[${name}] 사용자 ${users}명, 오픈 전 ${preOpenSeconds}초`);
  console.log(`  진입(사용자): 0.5초 안 ${within(500)}, 1초 안 ${within(1000)}(${pct(within(1000), users)}), `
    + `3초 안 ${within(3000)}, 10초 안 ${within(10000)}, 12초 안 ${within(12000)}`);
  console.log(`  진입 요청(연타 포함) ${enterRequests}건, 그중 오픈 1초 안 ${enterRequestsIn1s}건`);
  console.log(`  진입 100ms 칸 최대 ${maxBucket(enters, 100)}명, 1초 칸 최대 ${maxBucket(enters, 1000)}명`);
  console.log(`  상세 조회 ${details.length}건(오픈 전 ${preOpen}, 마지막 1분 ${lastMinute} = 초당 ${(lastMinute / 60).toFixed(0)}), `
    + `1초 칸 최대 ${maxBucket(details.map((t) => t - openAt), 1000)}건`);
}

function pct(part, whole) {
  return `${((part / whole) * 100).toFixed(1)}%`;
}

function maxBucket(values, size) {
  const counts = new Map();
  for (const value of values) {
    const key = Math.floor(value / size);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return Math.max(...counts.values());
}

// 시드 있는 32비트 난수(같은 시드면 같은 숫자)
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
