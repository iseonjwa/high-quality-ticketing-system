import { ApiError } from '@/api/apiError'
import { send } from '@/api/transport'
import {
  clearSession,
  saveTokens,
  sessionStore,
  tokenPairSchema,
  type TokenPair,
} from '@/auth/session'

let inFlight: Promise<string> | null = null

// 새 Access 토큰을 받는다. 이미 재발급 중이면 같은 결과를 기다린다.
//
// Note: 서버는 Refresh 토큰을 재발급마다 바꾸고, 같은 토큰으로 두 번째 재발급을 하면 401 TOKEN_REVOKED를 준다
// (RedisSessionStore의 ROTATE). 동시에 401을 받은 요청들이 각자 재발급하면 하나만 성공하고 나머지가 세션을 지운다.
// 그래서 진행 중인 재발급 하나를 모두가 기다린다.
export function refreshAccessToken(): Promise<string> {
  inFlight ??= runRefresh().finally(() => {
    inFlight = null
  })
  return inFlight
}

async function runRefresh(): Promise<string> {
  const { refreshToken } = sessionStore.getState()
  if (refreshToken === null) {
    throw signInRequired()
  }

  let tokens: TokenPair
  try {
    tokens = await send({
      method: 'POST',
      path: '/auth/refresh',
      body: { refreshToken },
      schema: tokenPairSchema,
    })
  } catch (error) {
    if (isSessionEnded(error) && isSameSession(refreshToken)) {
      clearSession()
    }
    throw error
  }

  // Note: 재발급을 기다리는 사이 로그아웃하거나 다시 로그인했으면 이 결과로 세션을 덮지 않는다.
  // 덮으면 로그아웃한 탭이 로그인 상태로 되살아나거나, 새로 로그인한 세션이 옛 토큰으로 바뀐다.
  if (!isSameSession(refreshToken)) {
    throw signInRequired()
  }
  saveTokens(tokens)
  return tokens.accessToken
}

function isSameSession(refreshToken: string): boolean {
  return sessionStore.getState().refreshToken === refreshToken
}

function signInRequired(): ApiError {
  return new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '로그인이 필요합니다' })
}

// 4xx는 이 Refresh 토큰으로 다시 시도해도 같은 결과다(만료·교체됨·잠김·임시 비밀번호).
// 5xx와 연결 실패는 토큰이 아직 유효할 수 있어 지우지 않는다(Redis 장애 중 503 등).
// 408·429는 잠시 뒤 다시 하면 되는 거절이라 세션을 끝내지 않는다.
function isSessionEnded(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status === 408 || error.status === 429) {
    return false
  }
  return error.status >= 400 && error.status < 500
}
