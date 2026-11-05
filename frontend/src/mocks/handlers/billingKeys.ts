import { http } from 'msw'
import { mockDb, nextMockId } from '@/mocks/db'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'
import { mockScenario } from '@/mocks/scenario'

// 자동결제 카드(빌링키) 등록·해지(`docs/design/payment/api.md` 머리말, payment-service BillingKeyController).
// 보유 여부를 조회하는 API는 서버에도 없다.

const AUTH_KEY_MAX_LENGTH = 300
const MOCK_CARD_LAST4 = '4242'

export const billingKeyHandlers = [
  // BillingKeyController.java:30-36. Idempotency-Key를 받지 않는다
  http.post('/api/v1/payments/billing-keys', async ({ request }) => {
    const user = requireUser(request)
    const body = await readBody(request)
    // RegisterRequest: @NotBlank @Size(max = 300) authKey
    const authKey = body.authKey
    if (typeof authKey !== 'string' || authKey.trim() === '') {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
        errors: [{ field: 'authKey', reason: '공백일 수 없습니다' }],
      })
    }
    if (authKey.length > AUTH_KEY_MAX_LENGTH) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
        errors: [{ field: 'authKey', reason: `${AUTH_KEY_MAX_LENGTH}자 이하여야 합니다` }],
      })
    }
    // BillingKeyService.java:41-44. PG 결과를 모르면 503이다
    if (mockScenario().billing === 'unavailable') {
      return fail(503, 'PG_UNAVAILABLE', '결제사 연결이 원활하지 않습니다')
    }
    // Mock PG는 authKey가 REJECT로 시작하면 422를 주고 어댑터가 거절로 바꾼다(MockPgAdapter.java:89)
    if (authKey.startsWith('REJECT')) {
      return fail(400, 'BILLING_KEY_REJECTED', '결제사가 카드 등록을 거절했습니다')
    }

    // BillingKeyService.java:37-59. 앞 ACTIVE 키를 REVOKED로 바꾸고 새 키를 ACTIVE로 저장한다
    for (const key of mockDb.billingKeys) {
      if (key.userId === user.userId && key.status === 'ACTIVE') {
        key.status = 'REVOKED'
      }
    }
    const billingKeyId = nextMockId()
    mockDb.billingKeys.push({ billingKeyId, userId: user.userId, status: 'ACTIVE' })
    return ok(
      { billingKeyId, cardLast4: MOCK_CARD_LAST4, status: 'ACTIVE' },
      { status: 201, message: '빌링키를 등록했습니다' },
    )
  }),

  // BillingKeyController.java:38-42. 카드가 없어도 200이고 revoked가 false다(BillingKeyService.java:61-65)
  http.delete('/api/v1/payments/billing-keys/me', ({ request }) => {
    const user = requireUser(request)
    const active = mockDb.billingKeys.find(
      (key) => key.userId === user.userId && key.status === 'ACTIVE',
    )
    if (active !== undefined) {
      active.status = 'REVOKED'
    }
    return ok({ revoked: active !== undefined }, { message: '빌링키를 해지했습니다' })
  }),
]
