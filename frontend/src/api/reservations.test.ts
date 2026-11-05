import { describe, expect, it } from 'vitest'
import { reservationDetailSchema } from '@/api/reservations'

describe('reservationDetailSchema', () => {
  it('holdExpiresAt이 null이어도 받는다(서버 컬럼이 nullable이다)', () => {
    const parsed = reservationDetailSchema.safeParse({
      reservationId: 1,
      reservationNo: 'R20261104-ABCDEF',
      scheduleId: 31,
      status: 'CONFIRMED',
      holdExpiresAt: null,
      totalAmount: 165000,
      seats: [],
      paymentToken: null,
      tickets: [],
    })

    expect(parsed.success).toBe(true)
  })
})
