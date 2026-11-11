import { describe, expect, it } from 'vitest'
import { ApiError, CLIENT_ERROR_CODES, isOutcomeUnknown } from '@/api/apiError'
import { describeError, ERROR_NOTICES } from '@/api/errorMessages'

function apiError(code: string, status = 409) {
  return new ApiError({ status, code, message: code, traceId: '4bf92f3577b34da6a3ce929d0e0e4736' })
}

const UNEXPECTED_MESSAGE = '문제가 생겼어요. 문의할 때 아래 번호를 알려 주세요'

describe('describeError', () => {
  it('SA 11-3의 모든 코드에 안내가 있다', () => {
    const saCodes = [
      'VALIDATION_FAILED',
      'UNAUTHORIZED',
      'TOKEN_EXPIRED',
      'TOKEN_REVOKED',
      'FORBIDDEN',
      'ENTRY_TOKEN_INVALID',
      'NOT_FOUND',
      'IDEMPOTENCY_KEY_REUSED',
      'RATE_LIMITED',
      'SERVICE_UNAVAILABLE',
      'EMAIL_DUPLICATED',
      'INVALID_CREDENTIALS',
      'SALE_NOT_OPEN',
      'QUEUE_NOT_JOINED',
      'QUEUE_NOT_READY',
      'SEAT_COUNT_MISMATCH',
      'SEAT_ALREADY_HELD',
      'PURCHASE_LIMIT_EXCEEDED',
      'RESERVATION_NOT_PAYABLE',
      'RESERVATION_NOT_CANCELABLE',
      'PAYMENT_TOKEN_INVALID',
      'PAYMENT_AMOUNT_MISMATCH',
      'PAYMENT_ALREADY_EXISTS',
      'PG_UNAVAILABLE',
      'LOTTERY_NOT_OPEN',
      'LOTTERY_ALREADY_ENTERED',
      'BILLING_KEY_REQUIRED',
    ]

    expect(saCodes.filter((code) => !(code in ERROR_NOTICES))).toEqual([])
  })

  it('Gateway 과부하 거절은 일시 불안정과 같은 안내로 보인다', () => {
    expect(describeError(apiError('GATEWAY_OVERLOADED', 503))).toEqual(
      describeError(apiError('SERVICE_UNAVAILABLE', 503)),
    )
  })

  it('정해진 안내가 있는 코드는 문의 번호를 보이지 않는다', () => {
    expect(describeError(apiError('SEAT_ALREADY_HELD'))).toEqual({
      tone: 'warning',
      message: '이미 선택된 좌석입니다. 남은 좌석은 그대로 두었어요',
      traceId: null,
    })
  })

  it('로그인 실패는 입력 칸 아래 문구로 보인다', () => {
    expect(describeError(apiError('INVALID_CREDENTIALS', 401)).tone).toBe('field')
  })

  it('표에 없는 코드와 응답 형식 어긋남은 예상하지 못한 오류로 보이고 traceId를 함께 준다', () => {
    for (const code of ['INTERNAL_SERVER_ERROR', CLIENT_ERROR_CODES.responseSchemaMismatch]) {
      expect(describeError(apiError(code, 500))).toEqual({
        tone: 'danger',
        message: UNEXPECTED_MESSAGE,
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
      })
    }
  })

  it('문의 번호가 없는 예상하지 못한 오류는 번호 안내 없이 다시 시도를 권한다', () => {
    const withoutTrace = {
      tone: 'danger',
      message: '문제가 생겼어요. 잠시 뒤 다시 시도해 주세요',
      traceId: null,
    }

    expect(describeError(new TypeError('x'))).toEqual(withoutTrace)
    expect(
      describeError(
        new ApiError({ status: 502, code: CLIENT_ERROR_CODES.unexpectedResponse, message: '' }),
      ),
    ).toEqual(withoutTrace)
  })

  it('응답을 못 받았거나 504면 코드와 상관없이 연결·지연 안내를 보인다', () => {
    expect(
      describeError(new ApiError({ status: 0, code: CLIENT_ERROR_CODES.networkError, message: '' }))
        .message,
    ).toBe('연결이 끊겼어요. 네트워크를 확인하고 다시 시도해 주세요')
    // Gateway의 504는 코드가 INTERNAL_SERVER_ERROR다
    expect(describeError(apiError('INTERNAL_SERVER_ERROR', 504)).message).toBe(
      '응답이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요',
    )
  })
})

describe('isOutcomeUnknown', () => {
  it('응답을 받지 못했거나 504일 때만 결과를 모르는 실패다', () => {
    expect(isOutcomeUnknown(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '' }))).toBe(
      true,
    )
    expect(isOutcomeUnknown(apiError('INTERNAL_SERVER_ERROR', 504))).toBe(true)
    expect(isOutcomeUnknown(apiError('PG_UNAVAILABLE', 503))).toBe(false)
    expect(isOutcomeUnknown(apiError('SEAT_ALREADY_HELD', 409))).toBe(false)
  })
})
