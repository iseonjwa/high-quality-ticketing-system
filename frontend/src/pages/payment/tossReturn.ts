import { z } from 'zod'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import type { PaymentAttempt, TossCheckout } from '@/auth/paymentAttempts'
import { isTossBuyerCancel } from '@/pages/payment/tossPaymentWindow'

// 토스 결제창에서 돌아온 주소(`/reservations/{id}/payment/toss/{success|fail}`)를 어떻게 다룰지 정하는 순수 함수.
// 저장소를 읽고 고치는 일과 이동은 TossReturnPage가 맡는다.

export type TossReturnResult = 'success' | 'fail'

export type TossReturnDecision =
  // 결제창을 연 기록과 맞다. 이 시도로 승인을 보낸다
  | { kind: 'approve'; attempt: PaymentAttempt & { clientPaymentRef: string } }
  // 성공 주소를 새로고침했다. 같은 paymentKey의 시도가 이미 저장돼 있어 그대로 이어 보낸다
  | { kind: 'resume' }
  // 기록이 없거나 값이 다르거나 빠졌다. 승인하지 않는다
  | { kind: 'mismatch' }
  | { kind: 'canceled' }
  | { kind: 'failed'; code: string | null }

export interface ResolveTossReturnInput {
  result: TossReturnResult
  params: URLSearchParams
  checkout: TossCheckout | null
  stored: PaymentAttempt | null
}

const MAX_PAYMENT_KEY_LENGTH = 200

export function resolveTossReturn({
  result,
  params,
  checkout,
  stored,
}: ResolveTossReturnInput): TossReturnDecision {
  if (result === 'fail') {
    const code = safeCode(params.get('code'))
    return isTossBuyerCancel(code) ? { kind: 'canceled' } : { kind: 'failed', code }
  }

  const paymentKey = params.get('paymentKey')
  if (paymentKey === null || paymentKey.length < 1 || paymentKey.length > MAX_PAYMENT_KEY_LENGTH) {
    return { kind: 'mismatch' }
  }
  if (stored?.clientPaymentRef === paymentKey) {
    return { kind: 'resume' }
  }
  const orderId = params.get('orderId')
  const amount = integerOf(params.get('amount'))
  if (checkout === null || orderId !== checkout.orderId || amount !== checkout.amount) {
    return { kind: 'mismatch' }
  }
  // Note: 멱등키는 돌아온 뒤에 만든다. 결제창을 닫고 다시 열면 paymentKey가 바뀌는데 같은 멱등키를 쓰면
  // 본문이 달라 409 IDEMPOTENCY_KEY_REUSED가 난다.
  return {
    kind: 'approve',
    attempt: { token: checkout.token, key: newIdempotencyKey(), clientPaymentRef: paymentKey },
  }
}

// "1000"처럼 정수 글자만 숫자로 읽는다. 그 밖은 null
function integerOf(value: string | null): number | null {
  return value !== null && /^\d{1,15}$/.test(value) ? Number(value) : null
}

// 주소로 아무 값이나 넣을 수 있어, 화면에 보일 코드는 토스 오류 코드 모양(대문자·숫자·_)만 받는다
function safeCode(value: string | null): string | null {
  return value !== null && /^[A-Z0-9_]{1,64}$/.test(value) ? value : null
}

// 결제 화면으로 돌아가며 넘기는 안내. 토스가 준 message는 주소로 아무 문구나 넣을 수 있어 넘기지 않는다
const tossReturnStateSchema = z.object({
  tossReturn: z.enum(['mismatch', 'canceled', 'failed']),
  tossCode: z.string().optional(),
})

export interface TossReturnNotice {
  kind: 'mismatch' | 'canceled' | 'failed'
  code: string | null
}

export function tossReturnState(
  decision: Exclude<TossReturnDecision, { kind: 'approve' } | { kind: 'resume' }>,
): { tossReturn: TossReturnNotice['kind']; tossCode?: string } {
  if (decision.kind === 'failed' && decision.code !== null) {
    return { tossReturn: 'failed', tossCode: decision.code }
  }
  return { tossReturn: decision.kind }
}

export function readTossReturnNotice(state: unknown): TossReturnNotice | null {
  const result = tossReturnStateSchema.safeParse(state)
  if (!result.success) {
    return null
  }
  return { kind: result.data.tossReturn, code: safeCode(result.data.tossCode ?? null) }
}
