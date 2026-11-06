// 측정용 토큰 서명(TOKEN_MODE=sign). 서버의 토큰 형식(SA 7-3, docs/design/queue/contracts.md 1절)과 같은 claim을 만든다.
// Note: 서명 키가 있어야 하므로 local 스택 전용이다. 배포 환경의 측정용 서명 방식은 아직 정하지 않았다(load-test.md 5절)
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { TOKEN_MODE, USER_ID_BASE } from './env.js';

const ACCESS_TTL_SECONDS = 1800;
const ENTRY_TTL_SECONDS = 600;

export function userIdOf(index) {
  return USER_ID_BASE + index;
}

// 선점·대기열 경로는 user-service를 부르지 않아 가입 행이 없어도 된다. 사용자 정보가 필요한 API는 이 토큰으로 재지 않는다
export function accessToken(userId, role = 'USER') {
  assertSignMode();
  const now = Math.floor(Date.now() / 1000);
  return signJwt({
    iss: 'iseonjwa-auth', aud: ['iseonjwa-api'], sub: String(userId), role, token_type: 'access',
    jti: uuid(), iat: now, exp: now + ACCESS_TTL_SECONDS,
  }, __ENV.JWT_ACCESS_SECRET);
}

// 대기열 API(진입 → READY → 토큰)를 거치지 않고 입장 토큰을 만든다. 대기열 부하를 빼고 뒤 구간만 잴 때 쓴다
export function entryToken(userId, scheduleId) {
  assertSignMode();
  const now = Math.floor(Date.now() / 1000);
  return signJwt({
    typ: 'entry', aud: ['gateway'], sub: String(userId), scheduleId: Number(scheduleId),
    jti: uuid(), iat: now, exp: now + ENTRY_TTL_SECONDS,
  }, __ENV.ENTRY_TOKEN_SECRET);
}

export function assertSigningKeys() {
  if (TOKEN_MODE === 'sign' && (!__ENV.JWT_ACCESS_SECRET || !__ENV.ENTRY_TOKEN_SECRET)) {
    throw new Error('TOKEN_MODE=sign에는 JWT_ACCESS_SECRET과 ENTRY_TOKEN_SECRET이 필요합니다(run.sh가 .env에서 읽는다)');
  }
}

// Idempotency-Key 등 측정용 UUID v4. 암호학적 난수가 필요 없어 Math.random으로 만든다
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

// HS256 JWT. 서버는 비밀값 문자열의 UTF-8 바이트를 키로 쓴다(TokenDecoders.signingKey)
function signJwt(claims, secret) {
  const input = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl')
    + '.' + encoding.b64encode(JSON.stringify(claims), 'rawurl');
  return input + '.' + crypto.hmac('sha256', secret, input, 'base64rawurl');
}

function assertSignMode() {
  if (TOKEN_MODE !== 'sign') {
    throw new Error(`TOKEN_MODE=${TOKEN_MODE}는 아직 지원하지 않습니다. 토큰 CSV 방식은 시나리오에서 따로 읽는다`);
  }
}
