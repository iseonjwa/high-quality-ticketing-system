// 요청 헤더와 응답 해석. 서버 응답은 SA 11-2 형식({ status, message, data, traceId })이다
import { BASE_URL } from './env.js';

export function url(path) {
  return `${BASE_URL}${path}`;
}

export function authHeaders(accessToken, extra = {}) {
  return Object.assign({ Authorization: `Bearer ${accessToken}` }, extra);
}

export function jsonHeaders(accessToken, extra = {}) {
  return authHeaders(accessToken, Object.assign({ 'Content-Type': 'application/json' }, extra));
}

// 응답의 data. JSON이 아니거나 data가 없으면 null
export function dataOf(res) {
  try {
    const body = res.json();
    return body && body.data !== undefined ? body.data : null;
  } catch (e) {
    return null;
  }
}

// 오류 응답의 code(SA 11-3). 응답 본문 전체를 파싱하지 않고 찾는다
export function errorCodeOf(res) {
  const match = /"code":"([A-Z_]+)"/.exec(res.body || '');
  return match ? match[1] : 'UNKNOWN';
}

// 실패한 요청을 다시 보내기까지 기다릴 시간(ms). 503에 Retry-After(초)가 있으면 그 값에 0~20%를 더하고, 그 밖에는 fallbackMillis다.
// Note: 같은 순간 503을 받은 사용자들이 Retry-After 그대로 다시 오면 한 시각에 다시 몰린다. 그래서 난수로 조금씩 흩는다.
// 대기열 503(QueueExceptionHandler)은 초 단위 숫자를 보낸다. 날짜 형식이나 헤더가 없는 503(예: Gateway 인증 503)은 fallbackMillis를 쓴다
export function retryDelayMillis(res, fallbackMillis = 3000) {
  if (res.status !== 503) {
    return fallbackMillis;
  }
  const seconds = Number(res.headers['Retry-After']);
  if (!res.headers['Retry-After'] || !Number.isFinite(seconds) || seconds < 0) {
    return fallbackMillis;
  }
  return seconds * 1000 * (1 + Math.random() * 0.2);
}

// 오류 응답 message를 짧은 태그로 바꾼다. 같은 code(예: 503 SERVICE_UNAVAILABLE)라도 낸 곳이 다르면 message가 다르다
const MESSAGE_TAGS = {
  '일시적으로 인증을 확인할 수 없습니다': 'auth_unavailable', // Gateway 인증의 Redis 벌크헤드·차단기 거절
  '일시적으로 서비스를 사용할 수 없습니다': 'service_unavailable', // 서비스 공통 503(CommonErrorCode)
  '요청을 처리할 수 없습니다': 'gateway_error', // Gateway 예외 처리기(라우팅·하위 서비스 연결 실패 등)
};

// messageTagOf가 낼 수 있는 값 전체. 시나리오가 태그별 집계 기준을 만들 때 쓴다
export const MESSAGE_TAG_VALUES = [...Object.values(MESSAGE_TAGS), 'other', 'none'];

// Note: 태그 값 종류가 늘면 k6 시계열이 그만큼 늘어난다. 모르는 message는 other, message가 없으면(연결 실패·JSON 아님) none으로 묶는다
export function messageTagOf(res) {
  const match = /"message":"((?:[^"\\]|\\.)*)"/.exec(res.body || '');
  if (!match) {
    return 'none';
  }
  return MESSAGE_TAGS[match[1]] || 'other';
}

// 결과 태그: 2xx는 ok, 4xx는 rejected(정상 거절일 수 있다), 5xx·연결 실패는 error
export function outcomeOf(res) {
  if (res.status >= 200 && res.status < 300) {
    return 'ok';
  }
  if (res.status >= 400 && res.status < 500) {
    return 'rejected';
  }
  return 'error';
}
