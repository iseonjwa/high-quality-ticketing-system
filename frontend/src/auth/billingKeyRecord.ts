import { z } from 'zod'

// 이 브라우저에서 등록한 자동결제 카드 기록. 서버에 보유 여부를 조회하는 API가 없어 등록 응답을 기억해 둔다.
// 카드 끝 네 자리와 등록 시각뿐이라 비밀값이 아니다. 사용자마다 따로 두고 로그아웃해도 남긴다(다시 로그인하면 보인다).
// 다른 브라우저에서 등록·해지했으면 맞지 않을 수 있다(화면이 그렇게 안내한다).

const KEY_PREFIX = 'iseonjwa.billingKey.'

const recordSchema = z.object({
  cardLast4: z.string().nullable(),
  registeredAt: z.string(),
})
export type BillingKeyRecord = z.infer<typeof recordSchema>

export function readBillingKeyRecord(userId: number): BillingKeyRecord | null {
  const raw = localStorage.getItem(KEY_PREFIX + userId)
  if (raw === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    parsed = undefined
  }
  const result = recordSchema.safeParse(parsed)
  if (!result.success) {
    localStorage.removeItem(KEY_PREFIX + userId)
    return null
  }
  return result.data
}

export function saveBillingKeyRecord(userId: number, record: BillingKeyRecord) {
  localStorage.setItem(KEY_PREFIX + userId, JSON.stringify(record))
}

export function removeBillingKeyRecord(userId: number) {
  localStorage.removeItem(KEY_PREFIX + userId)
}
