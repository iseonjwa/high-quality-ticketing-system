// 대기열 준비와 응답 해석. Q2·G1·Q3이 같이 쓴다
import http from 'k6/http';
import { fail } from 'k6';
import exec from 'k6/execution';
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

// Q2·Q3: VU 하나가 대기자 여러 명을 맡는다. 맡은 대기자마다 다음 폴링 시각(dueAt)과 헤더를 든다.
// Note: 대기자 한 명마다 VU를 두면 3만 VU가 필요해 발생기 메모리가 먼저 모자란다
export function waitersOfThisVu(users, vus, spreadSeconds) {
  const perVu = users / vus;
  const first = (exec.vu.idInTest - 1) * perVu;
  const now = Date.now();
  const list = [];
  for (let i = 0; i < perVu; i++) {
    list.push({
      userId: userIdOf(first + i),
      headers: authHeaders(accessToken(userIdOf(first + i))),
      dueAt: now + Math.random() * spreadSeconds * 1000,
    });
  }
  return list;
}

// 다음에 부를 대기자. 모두 끝났으면(dueAt null) null
export function earliestWaiter(list) {
  let found = null;
  for (const item of list) {
    if (item.dueAt !== null && (found === null || item.dueAt < found.dueAt)) {
      found = item;
    }
  }
  return found;
}
