// 대기열 준비와 응답 해석. Q2·G1·Q3이 같이 쓴다
import http from 'k6/http';
import { fail } from 'k6';
import { accessToken, userIdOf } from './tokens.js';
import { authHeaders, dataOf, url } from './http.js';

export function enterPath(scheduleId) {
  return `/api/v1/schedules/${scheduleId}/queue`;
}

export function positionPath(scheduleId) {
  return `/api/v1/schedules/${scheduleId}/queue/me`;
}

// setup에서 사용자 count명을 미리 진입시킨다. 측정 대상이 아니라 준비라서 묶음(http.batch)으로 빨리 보낸다.
// 실패가 있으면 측정을 시작하지 않는다(대기자 수가 틀리면 폴링 부하 계산이 틀린다)
export function enterAll(scheduleId, count, batchSize = 200) {
  let failed = 0;
  for (let start = 0; start < count; start += batchSize) {
    const requests = [];
    for (let index = start; index < Math.min(start + batchSize, count); index++) {
      requests.push(['POST', url(enterPath(scheduleId)), null,
        { headers: authHeaders(accessToken(userIdOf(index))), tags: { api: 'setup' } }]);
    }
    for (const res of http.batch(requests)) {
      if (res.status !== 201 && res.status !== 200) {
        failed++;
      }
    }
  }
  if (failed > 0) {
    fail(`대기자 준비 실패 ${failed}건 / ${count}명`);
  }
}

// 첫 사용자의 상태로 입장 스케줄러가 꺼졌는지 본다. 켜져 있으면 대기자가 초당 50명씩 READY가 되어 폴링 부하가 바뀐다
export function assertAdmissionStopped(scheduleId) {
  const res = http.get(url(positionPath(scheduleId)), { headers: authHeaders(accessToken(userIdOf(0))), tags: { api: 'setup' } });
  const data = dataOf(res);
  if (!data || data.status !== 'WAITING') {
    fail(`첫 대기자가 WAITING이 아닙니다(${data && data.status}). 입장 스케줄러를 끄고(loadtest/k6/compose.queue-hold.yml) 다시 실행합니다`);
  }
}
