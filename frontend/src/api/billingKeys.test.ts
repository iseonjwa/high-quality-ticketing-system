import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { registerBillingKey, revokeBillingKey } from '@/api/billingKeys'
import { hasActiveBillingKey, mockDb } from '@/mocks/db'
import { setMockScenario } from '@/mocks/scenario'
import { signInAsDemo } from '@/test/api'

// 목이 서버의 빌링키 등록·해지 동작(contracts-remaining 3절)과 같은지 확인한다.

const DEMO_USER_ID = 42

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('빌링키 목: 등록', () => {
  it('로그인하지 않으면 401이다', async () => {
    const error = await rejectionOf(registerBillingKey('demo-1'))

    expect(error.status).toBe(401)
  })

  describe('로그인했을 때', () => {
    beforeEach(async () => {
      await signInAsDemo()
    })

    it('카드 끝 네 자리 4242와 ACTIVE를 준다', async () => {
      const key = await registerBillingKey('demo-1')

      expect(key).toEqual({ billingKeyId: expect.any(Number), cardLast4: '4242', status: 'ACTIVE' })
      expect(hasActiveBillingKey(DEMO_USER_ID)).toBe(true)
    })

    it('다시 등록하면 새 번호가 되고 앞 키는 해지된다(사용자당 ACTIVE 하나)', async () => {
      const first = await registerBillingKey('demo-1')
      const second = await registerBillingKey('demo-2')

      expect(second.billingKeyId).not.toBe(first.billingKeyId)
      const mine = mockDb.billingKeys.filter((key) => key.userId === DEMO_USER_ID)
      expect(mine.map((key) => key.status)).toEqual(['REVOKED', 'ACTIVE'])
    })

    it('authKey가 비었거나 공백이거나 300자를 넘으면 400 VALIDATION_FAILED와 errors를 준다', async () => {
      for (const authKey of ['', '   ', 'a'.repeat(301)]) {
        const error = await rejectionOf(registerBillingKey(authKey))

        expect(error.status).toBe(400)
        expect(error.code).toBe('VALIDATION_FAILED')
        expect(error.fieldErrors.map((fieldError) => fieldError.field)).toEqual(['authKey'])
      }
      expect(hasActiveBillingKey(DEMO_USER_ID)).toBe(false)
    })

    it('authKey가 300자이면 받는다', async () => {
      await expect(registerBillingKey('a'.repeat(300))).resolves.toMatchObject({
        status: 'ACTIVE',
      })
    })

    it('REJECT로 시작하면 400 BILLING_KEY_REJECTED이고 앞 카드는 그대로다', async () => {
      await registerBillingKey('demo-1')

      const error = await rejectionOf(registerBillingKey('REJECT-1'))

      expect(error.status).toBe(400)
      expect(error.code).toBe('BILLING_KEY_REJECTED')
      expect(hasActiveBillingKey(DEMO_USER_ID)).toBe(true)
    })

    it('billing-unavailable 시나리오에서는 503 PG_UNAVAILABLE이고 카드가 생기지 않는다', async () => {
      setMockScenario({ billing: 'unavailable' })

      const error = await rejectionOf(registerBillingKey('demo-1'))

      expect(error.status).toBe(503)
      expect(error.code).toBe('PG_UNAVAILABLE')
      expect(hasActiveBillingKey(DEMO_USER_ID)).toBe(false)
    })
  })
})

describe('빌링키 목: 해지', () => {
  it('로그인하지 않으면 401이다', async () => {
    const error = await rejectionOf(revokeBillingKey())

    expect(error.status).toBe(401)
  })

  describe('로그인했을 때', () => {
    beforeEach(async () => {
      await signInAsDemo()
    })

    it('ACTIVE 카드가 있으면 해지하고 revoked가 true다', async () => {
      await registerBillingKey('demo-1')

      await expect(revokeBillingKey()).resolves.toEqual({ revoked: true })
      expect(hasActiveBillingKey(DEMO_USER_ID)).toBe(false)
    })

    it('카드가 없어도 에러가 아니고 revoked가 false다. 두 번 해지해도 같다', async () => {
      await expect(revokeBillingKey()).resolves.toEqual({ revoked: false })

      await registerBillingKey('demo-1')
      await revokeBillingKey()

      await expect(revokeBillingKey()).resolves.toEqual({ revoked: false })
    })

    it('다른 사용자의 카드는 건드리지 않는다', async () => {
      mockDb.billingKeys.push({ billingKeyId: 7, userId: 1, status: 'ACTIVE' })

      await expect(revokeBillingKey()).resolves.toEqual({ revoked: false })
      expect(hasActiveBillingKey(1)).toBe(true)
    })
  })
})
