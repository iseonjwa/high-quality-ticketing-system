import { describe, expect, it } from 'vitest'
import {
  readHeldSeats,
  removeAllHeldSeats,
  removeHeldSeats,
  saveHeldSeats,
  type StoredHold,
} from '@/auth/heldSeats'

function storedHold(reservationId: number): StoredHold {
  return {
    hold: {
      requestId: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11',
      reservationId,
      reservationNo: `R20261104-${String(reservationId).padStart(6, '0')}`,
      scheduleId: 31,
      seatIds: [1001, 1002],
      status: 'HELD',
      holdExpiresAt: '2026-11-04T20:10:00+09:00',
      totalAmount: 330000,
      paymentToken: `mock-payment.${reservationId}.1`,
      paymentTokenExpiresAt: '2026-11-04T20:09:50+09:00',
    },
    seats: [
      { seatId: 1001, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 1 },
      { seatId: 1002, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 2 },
    ],
  }
}

describe('선점 좌석 저장', () => {
  it('저장한 값을 같은 모양으로 읽는다', () => {
    saveHeldSeats(31, storedHold(1))

    expect(readHeldSeats(31)).toEqual(storedHold(1))
  })

  it('회차마다 따로 저장하고, 저장하지 않은 회차는 null이다', () => {
    saveHeldSeats(31, storedHold(1))
    saveHeldSeats(32, storedHold(2))

    expect(readHeldSeats(31)?.hold.reservationId).toBe(1)
    expect(readHeldSeats(32)?.hold.reservationId).toBe(2)
    expect(readHeldSeats(33)).toBeNull()
  })

  it('JSON이 아닌 값은 지우고 null을 준다', () => {
    sessionStorage.setItem('iseonjwa.heldSeats.31', '{깨진 값')

    expect(readHeldSeats(31)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.heldSeats.31')).toBeNull()
  })

  it('모양이 맞지 않는 값도 지우고 null을 준다', () => {
    sessionStorage.setItem('iseonjwa.heldSeats.31', JSON.stringify({ hold: {}, seats: [] }))

    expect(readHeldSeats(31)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.heldSeats.31')).toBeNull()
  })

  it('한 회차만 지운다', () => {
    saveHeldSeats(31, storedHold(1))
    saveHeldSeats(32, storedHold(2))

    removeHeldSeats(31)

    expect(readHeldSeats(31)).toBeNull()
    expect(readHeldSeats(32)).not.toBeNull()
  })

  it('모두 지울 때 선점 좌석만 지우고 다른 저장값은 둔다', () => {
    saveHeldSeats(31, storedHold(1))
    saveHeldSeats(32, storedHold(2))
    sessionStorage.setItem('iseonjwa.entryToken.31', 'keep')

    removeAllHeldSeats()

    expect(readHeldSeats(31)).toBeNull()
    expect(readHeldSeats(32)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.entryToken.31')).toBe('keep')
  })
})
