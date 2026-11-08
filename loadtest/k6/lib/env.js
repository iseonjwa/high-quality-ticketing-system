// 모든 시나리오가 같이 쓰는 환경변수. 시나리오 전용 값은 각 스크립트에서 읽는다.
// 실행 방법과 변수 목록: loadtest/k6/README.md
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

// sign: k6가 토큰을 직접 서명한다. local은 .env의 키, 배포는 측정 모드의 측정 전용 키를 쓰고 운영 키는 발생기에 두지 않는다(load-test.md 5절)
export const TOKEN_MODE = __ENV.TOKEN_MODE || 'sign';

// 가상 사용자 ID 시작값. 실행마다 바꾸면 같은 회차에서도 앞 실행의 사용자와 겹치지 않는다
export const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 1000000);

export function numberEnv(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : Number(value);
}

export function requireEnv(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} 환경변수가 필요합니다`);
  }
  return value;
}
