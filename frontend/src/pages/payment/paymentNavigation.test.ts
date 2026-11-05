import { describe, expect, it } from 'vitest'
import type { HeldSeatHold } from '@/api/reservations'
import {
  paymentNavigationState,
  readPaymentNavigation,
  type PaymentNavigation,
} from '@/pages/payment/paymentNavigation'
import { queueNavigationState } from '@/pages/queue/queueNavigation'

const HOLD: HeldSeatHold = {
  requestId: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11',
  reservationId: 1234,
  reservationNo: 'R20261104-001234',
  scheduleId: 31,
  seatIds: [1001, 1002],
  status: 'HELD',
  holdExpiresAt: '2026-11-04T20:05:00+09:00',
  totalAmount: 330000,
  paymentToken: 'mock-payment.1234.1',
  paymentTokenExpiresAt: '2026-11-04T20:04:50+09:00',
}

const SEATS = [
  { seatId: 1001, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 1 },
  { seatId: 1002, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 2 },
]

const CONCERT = {
  concertId: 7,
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}

describe('paymentNavigation', () => {
  it('만든 이동 값을 그대로 읽는다', () => {
    const navigation: PaymentNavigation = {
      hold: HOLD,
      seats: SEATS,
      concert: CONCERT,
      clockOffsetMillis: -1500,
    }

    expect(readPaymentNavigation(paymentNavigationState(navigation))).toEqual(navigation)
  })

  it('공연 요약이 없으면 이동 값에 넣지 않고 읽으면 concert가 null이다', () => {
    const state = paymentNavigationState({
      hold: HOLD,
      seats: SEATS,
      concert: null,
      clockOffsetMillis: null,
    })

    expect(state).not.toHaveProperty('concert')
    expect(readPaymentNavigation(state)).toEqual({
      hold: HOLD,
      seats: SEATS,
      concert: null,
      clockOffsetMillis: null,
    })
  })

  it('시계 차이가 없는 예전 이동 값도 읽고 차이는 null이다', () => {
    expect(readPaymentNavigation({ hold: HOLD, seats: SEATS })?.clockOffsetMillis).toBeNull()
  })

  it('공연 요약만 모양이 틀리면 결제 값은 읽고 concert만 null이다', () => {
    const read = readPaymentNavigation({ hold: HOLD, seats: SEATS, concert: { title: '' } })

    expect(read?.hold.reservationId).toBe(1234)
    expect(read?.concert).toBeNull()
  })

  it('좌석 화면이 넘긴 대기열 이동 값의 공연도 읽는다', () => {
    const read = readPaymentNavigation({
      hold: HOLD,
      seats: SEATS,
      ...queueNavigationState(CONCERT),
    })

    expect(read?.concert).toEqual(CONCERT)
  })

  it.each([
    ['값이 없다', null],
    ['빈 객체', {}],
    ['좌석이 없다', { hold: HOLD }],
    ['좌석이 배열이 아니다', { hold: HOLD, seats: 'A1' }],
    ['좌석 필드가 빠졌다', { hold: HOLD, seats: [{ seatId: 1001, section: 'A' }] }],
    ['결제 토큰이 비었다', { hold: { ...HOLD, paymentToken: '' }, seats: SEATS }],
    ['이미 확정된 선점이다', { hold: { ...HOLD, status: 'CONFIRMED' }, seats: SEATS }],
    ['예매 번호가 문자열이다', { hold: { ...HOLD, reservationId: '1234' }, seats: SEATS }],
  ])('모양이 다르면 null이다: %s', (_name, state) => {
    expect(readPaymentNavigation(state)).toBeNull()
  })
})
