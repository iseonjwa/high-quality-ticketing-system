import { HttpResponse } from 'msw'
import { ENTRY_TOKEN_MILLIS, mockDb, type MockUser } from '@/mocks/db'

// 목 핸들러가 함께 쓰는 응답·검증. 검증 실패는 실패 응답을 던져 핸들러를 바로 끝낸다(MSW가 던진 Response로 답한다).

let traceSequence = 0

function nextTraceId(): string {
  traceSequence += 1
  return traceSequence.toString(16).padStart(32, '0')
}

// SA 11-2 성공 형식
export function ok(
  data: unknown,
  init: { status?: number; message?: string; headers?: HeadersInit } = {},
) {
  const status = init.status ?? 200
  return HttpResponse.json(
    { status, message: init.message ?? '처리되었습니다', data, traceId: nextTraceId() },
    { status, headers: init.headers },
  )
}

// SA 11-2 실패 형식. errors는 검증 실패일 때만 넣는다
export function fail(
  status: number,
  code: string,
  message: string,
  extra: { errors?: { field: string; reason: string }[]; headers?: HeadersInit } = {},
) {
  const body = {
    status,
    code,
    message,
    ...(extra.errors && { errors: extra.errors }),
    traceId: nextTraceId(),
  }
  return HttpResponse.json(body, { status, headers: extra.headers })
}

// Gateway 역할: Access 토큰을 확인한다. 만료는 TOKEN_EXPIRED, 로그아웃된 토큰은 TOKEN_REVOKED,
// 그 밖(없음·모르는 토큰)은 UNAUTHORIZED다. Gateway처럼 만료를 블랙리스트보다 먼저 본다.
export function requireUser(request: Request): MockUser {
  const authorization = request.headers.get('Authorization')
  if (authorization === null || !authorization.startsWith('Bearer ')) {
    throw fail(401, 'UNAUTHORIZED', '인증이 필요합니다')
  }
  const token = authorization.slice('Bearer '.length)
  if (mockDb.expiredAccessTokens.has(token)) {
    throw fail(401, 'TOKEN_EXPIRED', '토큰이 만료됐습니다')
  }
  if (mockDb.revokedAccessTokens.has(token)) {
    throw fail(401, 'TOKEN_REVOKED', '로그아웃된 토큰입니다')
  }
  const userId = mockDb.accessTokens.get(token)
  const user = mockDb.users.find((candidate) => candidate.userId === userId)
  if (user === undefined) {
    throw fail(401, 'UNAUTHORIZED', '유효한 인증 토큰이 필요합니다')
  }
  return user
}

// 입장 토큰은 `mock-entry.{scheduleId}.{userId}.{expiresAtMillis}` 모양이다. Gateway처럼 저장소를 보지 않고
// 토큰만으로 확인한다. 그래서 새로고침으로 목 상태가 비어도 sessionStorage의 토큰이 그대로 통한다.
export function createEntryToken(
  scheduleId: number,
  userId: number,
  issuedAtMillis: number,
): string {
  return `mock-entry.${scheduleId}.${userId}.${issuedAtMillis + ENTRY_TOKEN_MILLIS}`
}

export function entryTokenExpiresAt(token: string): number {
  return Number(token.split('.')[3])
}

export function requireEntryToken(request: Request, userId: number, scheduleId: number) {
  const parts = request.headers.get('X-Entry-Token')?.split('.') ?? []
  const valid =
    parts.length === 4 &&
    parts[0] === 'mock-entry' &&
    Number(parts[1]) === scheduleId &&
    Number(parts[2]) === userId &&
    Number(parts[3]) > Date.now()
  if (!valid) {
    throw fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')
  }
}

const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function requireIdempotencyKey(request: Request): string {
  const key = request.headers.get('Idempotency-Key')
  if (key === null || !UUID_FORMAT.test(key)) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
      errors: [{ field: 'Idempotency-Key', reason: 'UUID여야 합니다' }],
    })
  }
  return key
}

export async function readBody(request: Request): Promise<Record<string, unknown>> {
  try {
    const body: unknown = await request.json()
    if (typeof body === 'object' && body !== null && !Array.isArray(body)) {
      return body as Record<string, unknown>
    }
  } catch {
    // 아래에서 같은 실패로 응답한다
  }
  throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
}

export function requireSchedule(scheduleId: number) {
  const schedule = mockDb.schedules.find((candidate) => candidate.scheduleId === scheduleId)
  if (schedule === undefined) {
    throw fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
  }
  return schedule
}
