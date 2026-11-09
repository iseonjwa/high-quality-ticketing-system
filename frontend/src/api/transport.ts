import type { z } from 'zod'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { failureEnvelopeSchema, successEnvelopeSchema } from '@/api/envelope'
import { recordRequest, setRequestTraceId } from '@/demo/requestLog'

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
  // 기본 true. false면 공통 응답 형식이 아닌 API(시연 패널이 Gateway로 부르는 Mock PG)라 성공 본문을 그대로 스키마로 본다.
  // 실패는 Gateway가 만든 공통 실패 형식(401·403)이면 그대로 풀고, 아니면 응답 상태와 본문의 code로 오류를 만든다
  envelope?: boolean
}

// 스트림 요청 옵션. 성공 본문을 푸는 일은 호출하는 쪽이 하므로 스키마가 없다.
export type StreamOptions = Omit<SendOptions<z.ZodType>, 'schema'>

const EVENT_STREAM = 'text/event-stream'
// Note: 스트림 요청도 JSON을 함께 받겠다고 알린다. 서버(Spring)는 Accept로 응답 형식을 고르는데, text/event-stream만
// 보내면 요청 전에 걸러진 400·429를 JSON으로 쓰지 못해 400은 빈 본문, 429는 500이 됐다(셀프 리뷰, bootJar로 재현)
const STREAM_ACCEPT = `${EVENT_STREAM}, application/json`

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
      throw options.envelope === false
        ? toRawApiError(response, payload)
        : toApiError(response, payload)
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

// 요청을 보내고 `text/event-stream` 응답을 본문을 읽기 전에 돌려준다. 본문 읽기와 닫기는 호출하는 쪽이 한다.
// 요청 전에 걸러진 실패(400·401·429)는 스트림이 아니라 공통 실패 형식 JSON이라 `send`와 같은 ApiError로 던진다.
// 요청 기록은 응답 상태를 받은 시점에 한 번 남긴다. 본문을 읽는 시간은 재지 않는다.
export async function openStream(options: StreamOptions): Promise<Response> {
  const startedAt = Date.now()
  const startedTick = performance.now()
  let status = 0
  let traceId: string | null = null
  let response: Response | undefined

  try {
    response = await fetchOrThrow(options, STREAM_ACCEPT)
    status = response.status
    if (!response.ok) {
      const payload = await readJson(response)
      traceId = readTraceId(payload)
      throw toApiError(response, payload)
    }
    if (!(response.headers.get('Content-Type') ?? '').startsWith(EVENT_STREAM)) {
      void response.body?.cancel().catch(() => undefined)
      throw new ApiError({
        status: response.status,
        code: CLIENT_ERROR_CODES.unexpectedResponse,
        message: '스트림이 아닌 200 응답입니다',
      })
    }
    return response
  } finally {
    if (!options.signal?.aborted) {
      const recordId = recordRequest({
        startedAt,
        method: options.method,
        path: loggedPath(options.path),
        status,
        durationMs: Math.round(performance.now() - startedTick),
        traceId,
      })
      if (recordId !== null && response !== undefined) {
        streamRecordIds.set(response, recordId)
      }
    }
  }
}

// 스트림 응답과 그 요청 기록을 잇는다. 응답을 읽는 쪽이 첫 이벤트에서 traceId를 알면 noteStreamTraceId로 채운다
const streamRecordIds = new WeakMap<Response, number>()

export function noteStreamTraceId(response: Response, traceId: string) {
  const recordId = streamRecordIds.get(response)
  if (recordId !== undefined) {
    setRequestTraceId(recordId, traceId)
  }
}

// Note: 쿼리 값은 남기지 않는다. 서버 공통 요청 로그 규칙과 같다.
function loggedPath(path: string): string {
  return (API_BASE_PATH + path).split('?')[0]
}

async function fetchOrThrow(
  options: StreamOptions,
  accept = 'application/json',
): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept, ...options.headers }
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

// 공통 형식이 아닌 API의 실패. Mock PG는 `{code}`만 담고, 장애 주입이 꺼진 프로필의 404는 Boot 기본 본문이다
function toRawApiError(response: Response, payload: unknown): ApiError {
  const failure = failureEnvelopeSchema.safeParse(payload)
  if (failure.success) {
    return toApiError(response, payload)
  }
  const code =
    typeof payload === 'object' &&
    payload !== null &&
    'code' in payload &&
    typeof payload.code === 'string'
      ? payload.code
      : CLIENT_ERROR_CODES.unexpectedResponse
  return new ApiError({
    status: response.status,
    code,
    message: `요청이 실패했습니다(${response.status})`,
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
  const data =
    options.envelope === false
      ? options.schema.safeParse(payload)
      : unwrapEnvelope(payload, options.schema)
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

function unwrapEnvelope<T extends z.ZodType>(payload: unknown, schema: T) {
  const envelope = successEnvelopeSchema.safeParse(payload)
  return envelope.success ? schema.safeParse(envelope.data.data) : envelope
}

function readTraceId(payload: unknown): string | null {
  if (typeof payload === 'object' && payload !== null && 'traceId' in payload) {
    return typeof payload.traceId === 'string' ? payload.traceId : null
  }
  return null
}
