import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { queryRetryDelay, shouldRetryQuery } from '@/api/queryClient'

function errorWith(status: number, retryAfterSeconds: number | null = null) {
  return new ApiError({ status, code: 'X', message: 'x', retryAfterSeconds })
}

describe('shouldRetryQuery', () => {
  it('연결 실패와 5xx만 다시 시도한다', () => {
    expect(shouldRetryQuery(0, errorWith(0))).toBe(true)
    expect(shouldRetryQuery(0, errorWith(503))).toBe(true)
    expect(shouldRetryQuery(0, errorWith(403))).toBe(false)
    expect(shouldRetryQuery(0, errorWith(409))).toBe(false)
    expect(shouldRetryQuery(0, errorWith(429))).toBe(false)
    expect(shouldRetryQuery(0, new Error('x'))).toBe(false)
  })

  it('두 번까지만 다시 시도한다', () => {
    expect(shouldRetryQuery(1, errorWith(503))).toBe(true)
    expect(shouldRetryQuery(2, errorWith(503))).toBe(false)
  })
})

describe('queryRetryDelay', () => {
  it('Retry-After가 있으면 그 초에 0~+20%를 더해 기다린다', () => {
    expect(queryRetryDelay(0, errorWith(503, 3), () => 0)).toBe(3000)
    expect(queryRetryDelay(0, errorWith(503, 3), () => 0.999)).toBe(3599)
  })

  it('Retry-After가 없으면 1초부터 두 배씩 늘리고 8초에서 멈춘다', () => {
    const noJitter = () => 0
    expect(queryRetryDelay(0, errorWith(502), noJitter)).toBe(1000)
    expect(queryRetryDelay(1, errorWith(502), noJitter)).toBe(2000)
    expect(queryRetryDelay(5, errorWith(502), noJitter)).toBe(8000)
  })
})
