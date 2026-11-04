import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { getMyReservations, getReservation } from '@/api/reservations'
import { SEAT_A1, SEAT_A2, signInAsDemo } from '@/test/api'
import { seedReservation } from '@/test/reservations'

// 예매 조회 목이 서버(ReservationController·ReservationService)의 정렬·검사·응답 모양을 따르는지 본다.

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('GET /reservations/me 목', () => {
  it('page는 0 이상, size는 1~100만 받고 벗어나면 400 VALIDATION_FAILED다', async () => {
    await signInAsDemo()

    for (const params of [{ page: -1 }, { size: 0 }, { size: 101 }, { page: 1.5 }, { size: NaN }]) {
      const error = await rejectionOf(getMyReservations(params))
      expect([params, error.status, error.code]).toEqual([params, 400, 'VALIDATION_FAILED'])
    }
    await expect(getMyReservations({ page: 0, size: 100 })).resolves.toMatchObject({ size: 100 })
    await expect(getMyReservations({ page: 0, size: 1 })).resolves.toMatchObject({ size: 1 })
  })

  it('최신순(만든 순서의 역순)으로 주고 page·size는 요청 값 그대로, totalElements는 전체 수다', async () => {
    await signInAsDemo()
    const [first, second, third] = [
      seedReservation({ status: 'EXPIRED' }),
      seedReservation({ status: 'CONFIRMED' }),
      seedReservation({ status: 'HELD' }),
    ]
    seedReservation({ userId: 1 })

    const page = await getMyReservations({ page: 1, size: 2 })

    expect(page.content.map((item) => item.reservationId)).toEqual([first.reservationId])
    expect(page).toMatchObject({ page: 1, size: 2, totalElements: 3 })
    const all = await getMyReservations({ page: 0, size: 5 })
    expect(all.content.map((item) => item.reservationId)).toEqual([
      third.reservationId,
      second.reservationId,
      first.reservationId,
    ])
    // 상태 필터가 없어 모든 상태가 섞여 나온다
    expect(all.content.map((item) => item.status)).toEqual(['HELD', 'CONFIRMED', 'EXPIRED'])
    // 범위를 넘긴 쪽은 빈 목록이다
    await expect(getMyReservations({ page: 5, size: 5 })).resolves.toMatchObject({
      content: [],
      page: 5,
      totalElements: 3,
    })
  })
})

describe('GET /reservations/{id} 목', () => {
  it('좌석은 ID 오름차순이고 holdExpiresAt은 +09:00이며 티켓은 CONFIRMED일 때만 있다', async () => {
    await signInAsDemo()
    const held = seedReservation({ seatIds: [SEAT_A2, SEAT_A1] })
    const confirmed = seedReservation({ seatIds: [SEAT_A2, SEAT_A1], status: 'CONFIRMED' })

    const heldDetail = await getReservation(held.reservationId)
    const confirmedDetail = await getReservation(confirmed.reservationId)

    expect(heldDetail.seats.map((seat) => seat.seatId)).toEqual([SEAT_A1, SEAT_A2])
    expect(heldDetail.holdExpiresAt).toMatch(/\+09:00$/)
    expect(heldDetail.tickets).toEqual([])
    expect(heldDetail.paymentToken).not.toBeNull()
    expect(confirmedDetail.tickets.map((ticket) => ticket.seatId).sort()).toEqual([
      SEAT_A1,
      SEAT_A2,
    ])
    expect(confirmedDetail.paymentToken).toBeNull()
  })

  it('남의 예매는 없는 예매와 같은 404이고 숫자가 아니거나 0 이하면 400이다', async () => {
    await signInAsDemo()
    const others = seedReservation({ userId: 1 })

    expect((await rejectionOf(getReservation(others.reservationId))).status).toBe(404)
    expect((await rejectionOf(getReservation(987654))).code).toBe('NOT_FOUND')
    expect((await rejectionOf(getReservation(0))).code).toBe('VALIDATION_FAILED')
  })
})
