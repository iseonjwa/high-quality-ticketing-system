import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { logout } from '@/api/auth'
import { getConcert, getScheduleSeats, type ScheduleSeats } from '@/api/concerts'
import { describeError } from '@/api/errorMessages'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { approvePayment, getPayment } from '@/api/payments'
import { getQueuePosition, issueEntryToken, joinQueue } from '@/api/queue'
import { getMyReservations, getReservation, holdSeats, type SeatHold } from '@/api/reservations'
import { readEntryToken } from '@/auth/entryTokens'
import { sessionStore } from '@/auth/session'
import { setMockScenario } from '@/mocks/scenario'
import {
  admitTo,
  BEFORE_OPEN_SCHEDULE_ID,
  CONCERT_ID,
  ON_SALE_SCHEDULE_ID,
  SEAT_A1,
  SEAT_A2,
  SEAT_A3,
  signInAsDemo,
} from '@/test/api'

// 화면 없이 MSW 목만으로 일반 예매 흐름(SA 2-4)과 실패 분기를 확인한다.

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

function availabilityOf(seats: ScheduleSeats, seatId: number): boolean | undefined {
  return seats.sections.flatMap((section) => section.seats).find((seat) => seat.seatId === seatId)
    ?.available
}

async function waitUntilConfirmed(reservationId: number, maxChecks = 5) {
  for (let check = 0; check < maxChecks; check++) {
    const reservation = await getReservation(reservationId)
    if (reservation.status === 'CONFIRMED') {
      return reservation
    }
  }
  throw new Error(`예매 ${reservationId}가 ${maxChecks}번 조회 안에 확정되지 않았다`)
}

function requireHeld(hold: SeatHold) {
  if (hold.status !== 'HELD') {
    throw new Error(`선점이 HELD여야 한다: ${hold.status}`)
  }
  return hold
}

describe('일반 예매 흐름', () => {
  it('로그인 → 공연 상세 → 대기 → 입장 → 좌석 선점 → 결제 → 예매 확정', async () => {
    await signInAsDemo()

    const concert = await getConcert(CONCERT_ID)
    const schedule = concert.schedules.find(
      (candidate) => candidate.scheduleId === ON_SALE_SCHEDULE_ID,
    )
    expect(schedule?.saleStatus).toBe('ON_SALE')

    // 대기열: 서버가 준 간격대로 READY가 될 때까지 조회한다
    let position = await joinQueue(ON_SALE_SCHEDULE_ID)
    expect(position.status).toBe('WAITING')
    let polls = 0
    while (position.status === 'WAITING') {
      expect(position.nextPollAfterMillis).toBeGreaterThan(0)
      position = await getQueuePosition(ON_SALE_SCHEDULE_ID)
      polls += 1
    }
    expect(polls).toBe(2)

    const admitted = await issueEntryToken(ON_SALE_SCHEDULE_ID)
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)?.token).toBe(admitted.entryToken)

    const seats = await getScheduleSeats(ON_SALE_SCHEDULE_ID)
    expect(availabilityOf(seats, SEAT_A1)).toBe(true)

    const hold = requireHeld(
      await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A2, SEAT_A1], newIdempotencyKey()),
    )
    expect(hold.seatIds).toEqual([SEAT_A1, SEAT_A2])
    expect(hold.totalAmount).toBe(330000)
    expect(Date.parse(hold.paymentTokenExpiresAt)).toBeLessThan(Date.parse(hold.holdExpiresAt))

    const payment = await approvePayment(
      { paymentToken: hold.paymentToken, amount: hold.totalAmount, method: 'CARD' },
      newIdempotencyKey(),
    )
    expect(payment.status).toBe('APPROVED')

    // 승인은 확정이 아니다. 확정은 이벤트로 늦게 반영되므로 CONFIRMED가 될 때까지 다시 조회한다
    const beforeConfirmed = await getReservation(hold.reservationId)
    expect(beforeConfirmed.status).toBe('HELD')
    expect(beforeConfirmed.tickets).toEqual([])
    const reservation = await waitUntilConfirmed(hold.reservationId)
    expect(reservation.tickets).toHaveLength(2)
    expect(reservation.paymentToken).toBeNull()

    const mine = await getMyReservations()
    expect(mine.content.map((item) => item.reservationId)).toEqual([hold.reservationId])
  })

  it('오픈 전 회차는 대기열 진입이 409 SALE_NOT_OPEN이다', async () => {
    await signInAsDemo()

    const error = await rejectionOf(joinQueue(BEFORE_OPEN_SCHEDULE_ID))

    expect(error.code).toBe('SALE_NOT_OPEN')
    expect(describeError(error).message).toBe('아직 오픈 전이에요')
  })

  it('READY 전에 토큰을 요청하면 409 QUEUE_NOT_READY, 줄에 없으면 404 QUEUE_NOT_JOINED다', async () => {
    await signInAsDemo()

    expect((await rejectionOf(issueEntryToken(ON_SALE_SCHEDULE_ID))).code).toBe('QUEUE_NOT_JOINED')
    await joinQueue(ON_SALE_SCHEDULE_ID)
    expect((await rejectionOf(issueEntryToken(ON_SALE_SCHEDULE_ID))).code).toBe('QUEUE_NOT_READY')
  })

  it('입장 토큰 재발급은 같은 토큰을 준다', async () => {
    await signInAsDemo()
    const first = await admitTo(ON_SALE_SCHEDULE_ID)

    const second = await issueEntryToken(ON_SALE_SCHEDULE_ID)

    expect(second.entryToken).toBe(first.entryToken)
  })

  it('로그아웃하면 이 탭의 로그인 토큰과 입장 토큰을 모두 지운다', async () => {
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)

    await logout()

    expect(sessionStore.getState()).toEqual({
      accessToken: null,
      refreshToken: null,
      expired: false,
    })
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).toBeNull()
  })

  it('로그아웃하면 모든 회차의 저장한 선점 좌석도 지우고 다른 저장값은 둔다', async () => {
    await signInAsDemo()
    sessionStorage.setItem('iseonjwa.heldSeats.31', '{}')
    sessionStorage.setItem('iseonjwa.heldSeats.32', '{}')
    sessionStorage.setItem('unrelated', 'keep')

    await logout()

    expect(sessionStorage.getItem('iseonjwa.heldSeats.31')).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.heldSeats.32')).toBeNull()
    expect(sessionStorage.getItem('unrelated')).toBe('keep')
  })
})

describe('실패 재현 스위치', () => {
  it('seat-conflict: 409 이선좌 뒤 다시 조회하면 겹친 좌석만 빠지고, 다시 고른 좌석은 새 키로 선점된다', async () => {
    setMockScenario({ seatHold: 'seat-conflict' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)

    const conflict = await rejectionOf(
      holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1, SEAT_A2], newIdempotencyKey()),
    )
    expect(conflict.status).toBe(409)
    expect(conflict.code).toBe('SEAT_ALREADY_HELD')
    expect(describeError(conflict)).toEqual({
      tone: 'warning',
      message: '이미 선택된 좌석입니다. 남은 좌석은 그대로 두었어요',
      traceId: null,
    })

    const seats = await getScheduleSeats(ON_SALE_SCHEDULE_ID)
    expect(availabilityOf(seats, SEAT_A1)).toBe(false)
    expect(availabilityOf(seats, SEAT_A2)).toBe(true)

    // 좌석이 바뀌었으니 새 멱등키로 보낸다(같은 키에 다른 좌석이면 IDEMPOTENCY_KEY_REUSED)
    const hold = await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A2, SEAT_A3], newIdempotencyKey())
    expect(hold.status).toBe('HELD')
  })

  it('같은 멱등키에 다른 좌석을 보내면 409 IDEMPOTENCY_KEY_REUSED다', async () => {
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const key = newIdempotencyKey()
    await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], key)

    const error = await rejectionOf(holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A2], key))

    expect(error.code).toBe('IDEMPOTENCY_KEY_REUSED')
  })

  it('payment-unknown: 202 UNKNOWN은 실패가 아니고, 결제 조회로 APPROVED를 확인한다', async () => {
    setMockScenario({ payment: 'unknown' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = requireHeld(await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey()))

    const pending = await approvePayment(
      { paymentToken: hold.paymentToken, amount: hold.totalAmount, method: 'CARD' },
      newIdempotencyKey(),
    )
    if (pending.status !== 'UNKNOWN') throw new Error(`UNKNOWN이어야 한다: ${pending.status}`)
    expect(pending.nextPollAfterSeconds).toBeGreaterThan(0)

    const detail = await getPayment(pending.paymentId)
    expect(detail.status).toBe('APPROVED')
    expect(detail.approvedAt).not.toBeNull()
    expect((await waitUntilConfirmed(hold.reservationId)).status).toBe('CONFIRMED')
  })

  it('응답을 못 받아 같은 멱등키로 다시 보내면 같은 결제를 돌려준다(504 뒤 재요청)', async () => {
    setMockScenario({ payment: 'unknown' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = requireHeld(await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey()))
    const body = {
      paymentToken: hold.paymentToken,
      amount: hold.totalAmount,
      method: 'CARD' as const,
    }
    const key = newIdempotencyKey()

    const first = await approvePayment(body, key)
    // 그 사이 결제가 승인돼도 같은 키 재요청은 처음 저장한 응답(202 UNKNOWN)을 그대로 돌려준다.
    // 결과는 결제 조회로 확인한다
    await getPayment(first.paymentId)
    const retried = await approvePayment(body, key)

    expect(retried.paymentId).toBe(first.paymentId)
    expect(retried.status).toBe('UNKNOWN')
    expect((await getPayment(first.paymentId)).status).toBe('APPROVED')
  })

  it('예매 조회로 다시 받은 결제 토큰은 옛 멱등키로 보내면 409이고, 새 키로 보내면 기존 결제에 연결된다', async () => {
    setMockScenario({ payment: 'unknown' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = requireHeld(await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey()))
    const key = newIdempotencyKey()
    const first = await approvePayment(
      { paymentToken: hold.paymentToken, amount: hold.totalAmount, method: 'CARD' },
      key,
    )

    // 새로고침 뒤 예매 조회로 토큰을 다시 받은 상황. 결제 토큰은 발급마다 다르다
    const reissued = (await getReservation(hold.reservationId)).paymentToken
    expect(reissued).not.toBeNull()
    expect(reissued).not.toBe(hold.paymentToken)
    const body = { paymentToken: reissued!, amount: hold.totalAmount, method: 'CARD' as const }

    expect((await rejectionOf(approvePayment(body, key))).code).toBe('IDEMPOTENCY_KEY_REUSED')
    expect((await approvePayment(body, newIdempotencyKey())).paymentId).toBe(first.paymentId)
  })

  it('pg-unavailable: 503 PG_UNAVAILABLE이고 좌석은 유지돼 다시 결제할 수 있다', async () => {
    setMockScenario({ payment: 'pg-unavailable' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = requireHeld(await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey()))
    const body = {
      paymentToken: hold.paymentToken,
      amount: hold.totalAmount,
      method: 'CARD' as const,
    }
    const key = newIdempotencyKey()

    const error = await rejectionOf(approvePayment(body, key))
    expect(error.status).toBe(503)
    expect(error.code).toBe('PG_UNAVAILABLE')
    expect(describeError(error).message).toBe(
      '결제사 연결이 원활하지 않아요. 좌석은 남은 시간 동안 유지돼요',
    )

    // 기록이 남지 않으므로 결제사가 회복되면 같은 키로 다시 보내 새로 승인받는다
    setMockScenario({ payment: 'approved' })
    expect((await approvePayment(body, key)).status).toBe('APPROVED')
  })

  it('payment-declined: 거절은 에러가 아니라 200 DECLINED이고, 같은 예매로 다시 결제하면 409 PAYMENT_ALREADY_CLOSED다', async () => {
    setMockScenario({ payment: 'declined' })
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = requireHeld(await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey()))
    const body = {
      paymentToken: hold.paymentToken,
      amount: hold.totalAmount,
      method: 'CARD' as const,
    }

    const declined = await approvePayment(body, newIdempotencyKey())
    if (declined.status !== 'DECLINED') throw new Error(`DECLINED여야 한다: ${declined.status}`)
    expect(declined.reasonCode).toBe('INSUFFICIENT_LIMIT')

    const retry = await rejectionOf(approvePayment(body, newIdempotencyKey()))
    expect(retry.code).toBe('PAYMENT_ALREADY_CLOSED')
  })
})
