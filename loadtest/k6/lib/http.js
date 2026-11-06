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
