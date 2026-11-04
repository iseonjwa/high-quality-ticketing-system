import { afterEach, describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from '@/api/idempotencyKey'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('newIdempotencyKey', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('서버가 받는 UUID 형식(8-4-4-4-12)의 v4 값을 만든다', () => {
    expect(newIdempotencyKey()).toMatch(UUID_V4)
  })

  it('부를 때마다 다른 값이다', () => {
    const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()))

    expect(keys.size).toBe(100)
  })

  it('HTTPS가 아니라 crypto.randomUUID가 없어도 만든다', () => {
    vi.spyOn(crypto, 'randomUUID').mockImplementation(() => {
      throw new Error('보안 컨텍스트가 아니다')
    })

    expect(newIdempotencyKey()).toMatch(UUID_V4)
  })
})
