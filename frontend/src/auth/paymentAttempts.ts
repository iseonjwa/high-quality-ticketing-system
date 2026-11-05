import { z } from 'zod'

// 결제 시도(결제 토큰과 멱등키)를 예매마다 sessionStorage에 둔다(ISE-116).
// 결제를 누른 뒤 새로고침하거나 뒤로 갔다 오면 화면 상태가 사라진다. 같은 토큰이면 같은 키로 다시 보내
// 서버가 처음 결과를 돌려주게 해, 결제가 이미 됐는데 새 키로 또 결제하는 일을 막는다.
// 결제 토큰을 담고 있어 로그아웃 때 지운다.

const KEY_PREFIX = 'iseonjwa.paymentAttempt.'

const paymentAttemptSchema = z.object({ token: z.string().min(1), key: z.string().min(1) })
export type PaymentAttempt = z.infer<typeof paymentAttemptSchema>

export function savePaymentAttempt(reservationId: number, attempt: PaymentAttempt) {
  sessionStorage.setItem(keyOf(reservationId), JSON.stringify(attempt))
}

// 읽을 수 없는 값은 지우고 null을 준다
export function readPaymentAttempt(reservationId: number): PaymentAttempt | null {
  const raw = sessionStorage.getItem(keyOf(reservationId))
  if (raw === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    parsed = undefined
  }
  const result = paymentAttemptSchema.safeParse(parsed)
  if (!result.success) {
    removePaymentAttempt(reservationId)
    return null
  }
  return result.data
}

export function removePaymentAttempt(reservationId: number) {
  sessionStorage.removeItem(keyOf(reservationId))
}

// 로그아웃 때 부른다. 결제 토큰의 사용자가 로그아웃한 사용자다
export function removeAllPaymentAttempts() {
  const keys: string[] = []
  for (let index = 0; index < sessionStorage.length; index++) {
    const key = sessionStorage.key(index)
    if (key?.startsWith(KEY_PREFIX)) {
      keys.push(key)
    }
  }
  keys.forEach((key) => sessionStorage.removeItem(key))
}

function keyOf(reservationId: number): string {
  return KEY_PREFIX + reservationId
}
