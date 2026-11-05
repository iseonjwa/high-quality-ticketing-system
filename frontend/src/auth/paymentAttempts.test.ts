import { describe, expect, it } from 'vitest'
import {
  readPaymentAttempt,
  removeAllPaymentAttempts,
  removePaymentAttempt,
  savePaymentAttempt,
} from '@/auth/paymentAttempts'

const ATTEMPT = { token: 'mock-payment.1001.1', key: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11' }

describe('결제 시도 저장', () => {
  it('저장한 값을 같은 모양으로 읽는다', () => {
    savePaymentAttempt(1001, ATTEMPT)

    expect(readPaymentAttempt(1001)).toEqual(ATTEMPT)
  })

  it('예매마다 따로 저장하고, 저장하지 않은 예매는 null이다', () => {
    savePaymentAttempt(1001, ATTEMPT)
    savePaymentAttempt(1002, { token: 'mock-payment.1002.1', key: 'other' })

    expect(readPaymentAttempt(1001)?.token).toBe('mock-payment.1001.1')
    expect(readPaymentAttempt(1002)?.key).toBe('other')
    expect(readPaymentAttempt(1003)).toBeNull()
  })

  it('다시 저장하면 덮어쓴다', () => {
    savePaymentAttempt(1001, ATTEMPT)
    savePaymentAttempt(1001, { token: 'mock-payment.1001.2', key: 'next' })

    expect(readPaymentAttempt(1001)).toEqual({ token: 'mock-payment.1001.2', key: 'next' })
  })

  it('JSON이 아닌 값은 지우고 null을 준다', () => {
    sessionStorage.setItem('iseonjwa.paymentAttempt.1001', '{깨진 값')

    expect(readPaymentAttempt(1001)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.paymentAttempt.1001')).toBeNull()
  })

  it('모양이 틀린 값은 지우고 null을 준다', () => {
    sessionStorage.setItem('iseonjwa.paymentAttempt.1001', JSON.stringify({ token: 'only' }))

    expect(readPaymentAttempt(1001)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.paymentAttempt.1001')).toBeNull()
  })

  it('한 예매만 지운다', () => {
    savePaymentAttempt(1001, ATTEMPT)
    savePaymentAttempt(1002, ATTEMPT)

    removePaymentAttempt(1001)

    expect(readPaymentAttempt(1001)).toBeNull()
    expect(readPaymentAttempt(1002)).not.toBeNull()
  })

  it('모두 지울 때 결제 시도만 지우고 다른 저장값은 둔다', () => {
    savePaymentAttempt(1001, ATTEMPT)
    savePaymentAttempt(1002, ATTEMPT)
    sessionStorage.setItem('iseonjwa.heldSeats.31', 'keep')

    removeAllPaymentAttempts()

    expect(readPaymentAttempt(1001)).toBeNull()
    expect(readPaymentAttempt(1002)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.heldSeats.31')).toBe('keep')
  })
})
