import { describe, expect, it } from 'vitest'
import {
  readTossReturnNotice,
  resolveTossReturn,
  tossReturnState,
} from '@/pages/payment/tossReturn'

const CHECKOUT = { token: 'mock-payment.1.1', orderId: 'order-1-abcdef', amount: 1000 }
const SUCCESS = 'paymentKey=tgen_1&orderId=order-1-abcdef&amount=1000'

function success(query: string, overrides: Partial<Parameters<typeof resolveTossReturn>[0]> = {}) {
  return resolveTossReturn({
    result: 'success',
    params: new URLSearchParams(query),
    checkout: CHECKOUT,
    stored: null,
    ...overrides,
  })
}

describe('resolveTossReturn', () => {
  it('성공 주소가 결제창 기록과 같으면 approve이고 새 멱등키와 paymentKey를 담는다', () => {
    const decision = success(SUCCESS)

    expect(decision.kind).toBe('approve')
    if (decision.kind === 'approve') {
      expect(decision.attempt.token).toBe(CHECKOUT.token)
      expect(decision.attempt.clientPaymentRef).toBe('tgen_1')
      expect(decision.attempt.key).toMatch(/^[0-9a-f-]{36}$/)
    }
  })

  it('amount가 문자열 "1000"이어도 정수로 대조한다', () => {
    expect(success('paymentKey=tgen_1&orderId=order-1-abcdef&amount=1000').kind).toBe('approve')
    expect(success('paymentKey=tgen_1&orderId=order-1-abcdef&amount=1000.5').kind).toBe('mismatch')
    expect(success('paymentKey=tgen_1&orderId=order-1-abcdef&amount=abc').kind).toBe('mismatch')
  })

  it('금액이 다르면 mismatch다', () => {
    expect(success('paymentKey=tgen_1&orderId=order-1-abcdef&amount=1').kind).toBe('mismatch')
  })

  it('orderId가 다르면 mismatch다', () => {
    expect(success('paymentKey=tgen_1&orderId=other-order&amount=1000').kind).toBe('mismatch')
  })

  it.each([
    ['paymentKey 빠짐', 'orderId=order-1-abcdef&amount=1000'],
    ['orderId 빠짐', 'paymentKey=tgen_1&amount=1000'],
    ['amount 빠짐', 'paymentKey=tgen_1&orderId=order-1-abcdef'],
    ['paymentKey 201자', `paymentKey=${'x'.repeat(201)}&orderId=order-1-abcdef&amount=1000`],
  ])('%s이면 mismatch다', (_name, query) => {
    expect(success(query).kind).toBe('mismatch')
  })

  it('결제창 기록이 없고 저장한 시도도 없으면 mismatch다', () => {
    expect(success(SUCCESS, { checkout: null }).kind).toBe('mismatch')
  })

  it('결제창 기록은 없지만 같은 paymentKey의 시도가 저장돼 있으면 resume이다', () => {
    const stored = { token: CHECKOUT.token, key: 'k', clientPaymentRef: 'tgen_1' }

    expect(success(SUCCESS, { checkout: null, stored })).toEqual({ kind: 'resume' })
  })

  it('저장한 시도의 paymentKey가 다르면 resume이 아니다', () => {
    const stored = { token: CHECKOUT.token, key: 'k', clientPaymentRef: 'tgen_other' }

    expect(success(SUCCESS, { checkout: null, stored }).kind).toBe('mismatch')
  })

  it('fail 주소의 PAY_PROCESS_CANCELED는 canceled다', () => {
    const decision = resolveTossReturn({
      result: 'fail',
      params: new URLSearchParams('code=PAY_PROCESS_CANCELED&message=취소'),
      checkout: CHECKOUT,
      stored: null,
    })

    expect(decision).toEqual({ kind: 'canceled' })
  })

  it('fail 주소의 그 밖의 코드는 failed이고, 코드 모양이 아니면 null이다', () => {
    const fail = (query: string) =>
      resolveTossReturn({
        result: 'fail',
        params: new URLSearchParams(query),
        checkout: null,
        stored: null,
      })

    expect(fail('code=REJECT_CARD_COMPANY&message=x')).toEqual({
      kind: 'failed',
      code: 'REJECT_CARD_COMPANY',
    })
    expect(fail('code=<script>')).toEqual({ kind: 'failed', code: null })
    expect(fail('')).toEqual({ kind: 'failed', code: null })
  })
})

describe('결제 화면으로 넘기는 안내', () => {
  it('state로 만들고 다시 읽으면 같은 안내다', () => {
    expect(readTossReturnNotice(tossReturnState({ kind: 'failed', code: 'REJECT' }))).toEqual({
      kind: 'failed',
      code: 'REJECT',
    })
    expect(readTossReturnNotice(tossReturnState({ kind: 'canceled' }))).toEqual({
      kind: 'canceled',
      code: null,
    })
    expect(readTossReturnNotice(tossReturnState({ kind: 'mismatch' }))).toEqual({
      kind: 'mismatch',
      code: null,
    })
  })

  it('안내가 아닌 state는 null이다', () => {
    expect(readTossReturnNotice(null)).toBeNull()
    expect(readTossReturnNotice({ tossReturn: 'other' })).toBeNull()
  })
})
