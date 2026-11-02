import type { z } from 'zod'
import { ApiError, isApiError } from '@/api/apiError'
import { send, type SendOptions } from '@/api/transport'
import { readEntryToken, removeEntryToken } from '@/auth/entryTokens'
import { refreshAccessToken } from '@/auth/refresh'
import { clearSession, sessionStore } from '@/auth/session'

export interface ApiRequestOptions<T extends z.ZodType> extends Omit<SendOptions<T>, 'headers'> {
  // 기본 true. false면 Authorization을 붙이지 않고 401에도 재발급하지 않는다(공개 API, 로그인·재발급 자체).
  auth?: boolean
  // 선점·결제처럼 멱등키가 필요한 요청. 호출하는 쪽이 의도마다 하나 만들어 재시도에도 같은 값을 넘긴다.
  idempotencyKey?: string
  // 입장 토큰이 필요한 요청(좌석 조회·선점)의 회차. 저장한 입장 토큰을 X-Entry-Token으로 붙인다.
  entryScheduleId?: number
}

// 화면이 쓰는 요청 함수. 공통 응답을 풀어 `data`를 돌려주고, 실패는 ApiError로 던진다.
export async function apiRequest<T extends z.ZodType>(
  options: ApiRequestOptions<T>,
): Promise<z.output<T>> {
  const { auth = true, idempotencyKey, entryScheduleId, body, ...sendOptions } = options
  const headers: Record<string, string> = {}
  if (idempotencyKey !== undefined) {
    headers['Idempotency-Key'] = idempotencyKey
  }
  const entry =
    entryScheduleId === undefined
      ? null
      : { scheduleId: entryScheduleId, token: requireEntryToken(entryScheduleId) }
  if (entry !== null) {
    headers['X-Entry-Token'] = entry.token
  }
  // 재발급을 기다리는 사이 호출한 쪽이 본문 객체를 바꿔도 재시도가 같은 본문을 보내도록 복사해 둔다.
  // 같은 멱등키에 다른 본문은 409 IDEMPOTENCY_KEY_REUSED다.
  const request = { ...sendOptions, headers, body: structuredClone(body) }

  if (!auth) {
    return send(request)
  }

  try {
    return await sendWithAuth(request)
  } catch (error) {
    if (entry !== null && isApiError(error, 'ENTRY_TOKEN_INVALID')) {
      removeEntryToken(entry.scheduleId, entry.token)
    }
    throw error
  }
}

// Note: 재시도는 처음 요청과 같은 headers·본문을 쓴다. 401은 Gateway가 서비스에 넘기기 전에 주므로 첫 요청은
// 서비스에 닿지 않았다. 그래도 같은 키를 쓰는 것은 "의도 하나에 키 하나"를 지키기 위해서다. 재시도가 응답 없이
// 끊겨 화면이 그 키로 다시 보낼 때 서버에 저장된 키와 같아야 한다. 재시도는 한 번뿐이라 401이 반복돼도 무한히 돌지 않는다.
async function sendWithAuth<T extends z.ZodType>(options: SendOptions<T>): Promise<z.output<T>> {
  const accessToken = await currentAccessToken()
  try {
    return await send(withBearer(options, accessToken))
  } catch (error) {
    if (!isApiError(error) || error.status !== 401) {
      throw error
    }
    const renewedToken = await renewAfterUnauthorized(accessToken)
    return await sendRenewed(withBearer(options, renewedToken))
  }
}

async function currentAccessToken(): Promise<string> {
  const { accessToken, refreshToken } = sessionStore.getState()
  if (accessToken !== null) {
    return accessToken
  }
  if (refreshToken !== null) {
    // 새로고침 뒤 첫 요청이다. Access 토큰은 메모리에만 있어 사라졌으므로 먼저 재발급한다.
    return refreshAccessToken()
  }
  throw new ApiError({ status: 401, code: 'UNAUTHORIZED', message: '로그인이 필요합니다' })
}

// Gateway는 만료에 TOKEN_EXPIRED, 위조·형식 오류에 UNAUTHORIZED, 로그아웃된 토큰에 TOKEN_REVOKED를 준다(SA 11-3).
// 어느 코드든 401이면 재발급을 한 번 시도한다. 재발급이 안 되는 경우는 재발급 응답이 알려 주고,
// 코드로 나누지 않아 서버가 코드를 더하거나 바꿔도 깨지지 않는다.
async function renewAfterUnauthorized(rejectedToken: string): Promise<string> {
  const latestToken = sessionStore.getState().accessToken
  if (latestToken !== null && latestToken !== rejectedToken) {
    // 이 요청이 나간 사이 다른 요청이 이미 재발급했다. 다시 재발급하지 않고 새 토큰으로 보낸다.
    return latestToken
  }
  return refreshAccessToken()
}

async function sendRenewed<T extends z.ZodType>(options: SendOptions<T>): Promise<z.output<T>> {
  try {
    return await send(options)
  } catch (error) {
    // 방금 받은 토큰도 거절되면 이 세션으로는 더 할 수 없다. 로그인 화면으로 보내도록 세션을 지운다.
    if (isApiError(error) && error.status === 401) {
      clearSession()
    }
    throw error
  }
}

function withBearer<T extends z.ZodType>(
  options: SendOptions<T>,
  accessToken: string,
): SendOptions<T> {
  return { ...options, headers: { ...options.headers, Authorization: `Bearer ${accessToken}` } }
}

// 저장한 입장 토큰이 없으면 요청을 보내지 않고 Gateway와 같은 403으로 끝낸다.
// 화면은 서버가 준 403과 같은 길(대기열로 돌아가기)로 처리한다. 만료 판단은 Gateway에 맡긴다(entryTokens.ts).
function requireEntryToken(scheduleId: number): string {
  const entry = readEntryToken(scheduleId)
  if (entry === null) {
    throw new ApiError({
      status: 403,
      code: 'ENTRY_TOKEN_INVALID',
      message: '입장 토큰이 없습니다',
    })
  }
  return entry.token
}
