import type { FieldError } from '@/api/envelope'

// 서버가 준 코드(SA 11-3)가 아니라 브라우저에서 정하는 코드다.
export const CLIENT_ERROR_CODES = {
  // 응답을 받지 못했다. 서버가 처리했는지 모른다.
  networkError: 'NETWORK_ERROR',
  // 실패 응답이 공통 형식이 아니다(중계 서버의 502·504 HTML 등).
  unexpectedResponse: 'UNEXPECTED_RESPONSE',
  // 성공 응답이 계약 스키마와 다르다.
  responseSchemaMismatch: 'RESPONSE_SCHEMA_MISMATCH',
} as const

interface ApiErrorInit {
  status: number
  code: string
  message: string
  fieldErrors?: FieldError[]
  traceId?: string | null
  retryAfterSeconds?: number | null
}

export class ApiError extends Error {
  // HTTP 상태 코드. 응답을 받지 못했으면 0이다.
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  readonly traceId: string | null
  readonly retryAfterSeconds: number | null

  constructor(init: ApiErrorInit) {
    super(init.message || init.code)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.traceId = init.traceId ?? null
    this.retryAfterSeconds = init.retryAfterSeconds ?? null
  }
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code)
}

// 요청이 서버에서 처리됐는지 모르는 실패다. 응답을 받지 못했거나 Gateway가 응답 시한(3초)을 넘겨 504를 줬다.
// Gateway의 504는 공통 형식이지만 코드가 INTERNAL_SERVER_ERROR라(GatewayExceptionHandler) 상태로 가린다.
// 선점·결제에서 이 오류는 실패가 아니라 "확인 중"이다. 같은 멱등키로 다시 보내거나 결과를 조회한다.
export function isOutcomeUnknown(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status === 504)
}
