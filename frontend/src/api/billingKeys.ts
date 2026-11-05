import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId } from '@/api/envelope'

// 자동결제 카드(빌링키) 등록·해지(`docs/design/payment/api.md` 머리말, payment-service BillingKeyController).
// 추첨에 당첨되면 이 카드로 자동결제된다. 보유 여부를 조회하는 API는 없다.

const billingKeySchema = z.object({
  billingKeyId: entityId,
  // Mock PG는 늘 4242다. PG가 주지 않으면 null이다
  cardLast4: z.string().nullable(),
  status: z.literal('ACTIVE'),
})
export type BillingKey = z.infer<typeof billingKeySchema>

// 등록. 멱등키를 받지 않는다. 다시 등록하면 앞 카드는 해지되고 새 카드가 된다(사용자당 ACTIVE 하나).
// authKey는 원래 PG 결제창이 주는 인증 값이다. 시연 결제(Mock PG)는 공백이 아니면 무엇이든 받는다.
export function registerBillingKey(authKey: string) {
  return apiRequest({
    method: 'POST',
    path: '/payments/billing-keys',
    body: { authKey },
    schema: billingKeySchema,
  })
}

// 해지. 카드가 없었어도 200이고 revoked가 false다
export function revokeBillingKey() {
  return apiRequest({
    method: 'DELETE',
    path: '/payments/billing-keys/me',
    schema: z.object({ revoked: z.boolean() }),
  })
}
