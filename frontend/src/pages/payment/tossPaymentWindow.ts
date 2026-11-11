// 토스 결제창(결제창 구버전, API 개별 연동 키) 감싸기. SDK는 토스 모드에서만 동적으로 불러
// Mock 모드의 번들과 외부 호출에 영향이 없게 한다.
// Note: 위젯(`widgets()`)은 쓰지 않는다. 위젯은 `test_gck_` 키용이고 우리 키는 `test_ck_`다.
// successUrl·failUrl을 주면 리다이렉트 방식이라 이행(resolve)은 결제창을 열었다는 뜻일 뿐이고,
// 결과는 돌아오는 주소(TossReturnPage)로 온다.

export interface OpenTossPaymentWindowOptions {
  clientKey: string
  orderId: string
  amount: number
  orderName: string
  // 오리진을 포함한 절대 주소
  successUrl: string
  failUrl: string
}

export async function openTossPaymentWindow(options: OpenTossPaymentWindowOptions): Promise<void> {
  const { loadTossPayments, ANONYMOUS } = await import('@tosspayments/tosspayments-sdk')
  const tossPayments = await loadTossPayments(options.clientKey)
  await tossPayments.payment({ customerKey: ANONYMOUS }).requestPayment({
    method: 'CARD',
    amount: { currency: 'KRW', value: options.amount },
    orderId: options.orderId,
    orderName: options.orderName,
    successUrl: options.successUrl,
    failUrl: options.failUrl,
  })
}

// SDK가 던지는 오류는 `code`가 있는 객체다
export function tossErrorCode(error: unknown): string | null {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const { code } = error as { code: unknown }
    return typeof code === 'string' ? code : null
  }
  return null
}

// 구매자가 창을 닫으면 USER_CANCEL이 던져질 수 있고, 실패 주소로 PAY_PROCESS_CANCELED가 올 수도 있다
export function isTossBuyerCancel(code: string | null): boolean {
  return code === 'USER_CANCEL' || code === 'PAY_PROCESS_CANCELED'
}
