// Mock PG 장애 주입(mock-pg.md 5절). loadtest 프로필에서만 열린다(compose.mockpg-loadtest.yml)
import http from 'k6/http';
import { fail } from 'k6';

// local: 호스트에 열린 8090. 배포 환경은 Mock PG 포트를 밖에 열지 않으므로 서버 안에서 따로 건다
const MOCK_PG_URL = __ENV.MOCK_PG_URL === undefined ? 'http://host.docker.internal:8090' : __ENV.MOCK_PG_URL;

// 예: setFault({ target: 'APPROVE', mode: 'DELAY', delayMs: 300 }). rate·count를 빼면 모든 호출에 건다
export function setFault(rule) {
  if (!MOCK_PG_URL) {
    console.warn(`MOCK_PG_URL이 비어 장애 주입을 건너뜁니다: ${JSON.stringify(rule)}`);
    return;
  }
  const res = http.post(`${MOCK_PG_URL}/mock-pg/faults`, JSON.stringify(rule),
    { headers: { 'Content-Type': 'application/json' }, tags: { api: 'setup' } });
  if (res.status < 200 || res.status >= 300) {
    fail(`Mock PG 장애 주입 실패: ${res.status}. mock-pg를 loadtest 프로필로 띄웠는지 본다(loadtest/k6/compose.mockpg-loadtest.yml)`);
  }
}

// 승인 지연만 거는 경우가 많아 따로 둔다. 0이면 지연 없음(NONE)
export function setApproveDelay(delayMs) {
  setFault(delayMs > 0 ? { target: 'APPROVE', mode: 'DELAY', delayMs } : { target: 'APPROVE', mode: 'NONE' });
}
