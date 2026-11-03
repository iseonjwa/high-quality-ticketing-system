import type { z } from 'zod'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { failureEnvelopeSchema, successEnvelopeSchema } from '@/api/envelope'
import { recordRequest } from '@/demo/requestLog'

export const API_BASE_PATH = '/api/v1'

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE'

export interface SendOptions<T extends z.ZodType> {
  method: HttpMethod
  // API_BASE_PATH 뒤의 경로. 예: `/auth/login`
  path: string
  // 성공 응답 `data`의 스키마
  schema: T
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

// 요청 한 번을 보내고 공통 응답을 푼다. 인증·재시도는 하지 않는다(`client.ts`가 맡는다).
// 시연 모드용 요청 기록은 결과와 상관없이 요청마다 한 번 남긴다.
export async function send<T extends z.ZodType>(options: SendOptions<T>): Promise<z.output<T>> {
  const startedAt = Date.now()
  const startedTick = performance.now()
  let status = 0
  let traceId: string | null = null

  try {
    const response = await fetchOrThrow(options)
    status = response.status
    const payload = await readJson(response)
    traceId = readTraceId(payload)

    if (!response.ok) {
      throw toApiError(response, payload)
    }
    return parseData(response, payload, options)
  } finally {
    // 취소는 TanStack Query가 한 것이므로 사용자가 보낸 요청으로 세지 않는다.
    if (!options.signal?.aborted) {
      recordRequest({
        startedAt,
        method: options.method,
        path: loggedPath(options.path),
        status,
        durationMs: Math.round(performance.now() - startedTick),
        traceId,
      })
    }
  }
}

// Note: 쿼리 값은 남기지 않는다. 서버 공통 요청 로그 규칙과 같다.
function loggedPath(path: string): string {
  return (API_BASE_PATH + path).split('?')[0]
}

async function fetchOrThrow(options: SendOptions<z.ZodType>): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  let body: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  try {
    // Note: 같은 출처 상대 경로지만 테스트(Node fetch)는 상대 경로를 받지 않아 현재 출처로 절대 주소를 만든다.
    const url = new URL(API_BASE_PATH + options.path, window.location.origin)
    return await fetch(url, { method: options.method, headers, body, signal: options.signal })
  } catch (error) {
    // 취소는 TanStack Query가 직접 처리하므로 그대로 던진다.
    if (options.signal?.aborted) {
      throw error
    }
    throw new ApiError({
      status: 0,
      code: CLIENT_ERROR_CODES.networkError,
      message: '서버에 연결하지 못했습니다',
    })
  }
}

async function readJson(response: Response): Promise<unknown> {
  let text: string
  try {
    text = await response.text()
  } catch {
    // 상태 줄은 받았지만 본문을 받다가 끊겼다. 서버가 처리했는지 모르는 것은 연결 실패와 같다
    throw new ApiError({
      status: 0,
      code: CLIENT_ERROR_CODES.networkError,
      message: '응답을 끝까지 받지 못했습니다',
    })
  }
  if (text === '') {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function toApiError(response: Response, payload: unknown): ApiError {
  const failure = failureEnvelopeSchema.safeParse(payload)
  if (!failure.success) {
    return new ApiError({
      status: response.status,
      code: CLIENT_ERROR_CODES.unexpectedResponse,
      message: `공통 실패 형식이 아닌 ${response.status} 응답입니다`,
    })
  }

  // HTTP 상태는 본문의 status가 아니라 응답 상태를 쓴다. 둘은 같아야 하지만 판단 기준은 응답 상태다.
  return new ApiError({
    status: response.status,
    code: failure.data.code,
    message: failure.data.message,
    fieldErrors: failure.data.errors,
    traceId: failure.data.traceId,
    retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
  })
}

// Retry-After는 정수 초다(SA 11-5 대기열). HTTP 날짜 형식은 서버가 쓰지 않아 받지 않는다.
function parseRetryAfter(value: string | null): number | null {
  if (value === null || !/^\d+$/.test(value.trim())) {
    return null
  }
  return Number.parseInt(value, 10)
}

function parseData<T extends z.ZodType>(
  response: Response,
  payload: unknown,
  options: SendOptions<T>,
): z.output<T> {
  const envelope = successEnvelopeSchema.safeParse(payload)
  const data = envelope.success ? options.schema.safeParse(envelope.data.data) : envelope
  if (!data.success) {
    // Note: 응답 스키마 검증은 계약 어긋남을 화면이 깨지기 전에 드러내려는 것이다(ADR 0040 3절).
    // 필드를 더하는 변경은 통과한다(z.object는 모르는 필드를 버린다). 빠지거나 타입이 바뀌면 여기서 멈춘다.
    console.error(
      `[api] ${options.method} ${options.path} 응답이 계약과 다릅니다`,
      data.error.issues,
    )
    throw new ApiError({
      status: response.status,
      code: CLIENT_ERROR_CODES.responseSchemaMismatch,
      message: '응답 형식이 계약과 다릅니다',
      traceId: readTraceId(payload),
    })
  }
  return data.data
}

function readTraceId(payload: unknown): string | null {
  if (typeof payload === 'object' && payload !== null && 'traceId' in payload) {
    return typeof payload.traceId === 'string' ? payload.traceId : null
  }
  return null
}
