import { http } from 'msw'
import {
  advanceConfirmation,
  HOLD_MILLIS,
  mockDb,
  nextMockId,
  PAYMENT_TOKEN_MARGIN_MILLIS,
  settleExpiredHolds,
  toKstIso,
  type MockReservation,
} from '@/mocks/db'
import { saleStatusOf } from '@/mocks/handlers/concerts'
import {
  fail,
  ok,
  readBody,
  requireEntryToken,
  requireIdempotencyKey,
  requireSchedule,
  requireUser,
} from '@/mocks/respond'
import { mockScenario } from '@/mocks/scenario'

// 동기형 선점(`docs/design/reservation/contracts.md`)과 예매 조회(SA 11-5).

// 결제 토큰 `mock-payment.{reservationId}.{발급 번호}`. 실제 발급기처럼 발급마다 값이 달라진다(무작위 jti).
// 결제 서버는 토큰 원문까지 멱등 비교에 넣으므로, 다시 받은 토큰을 옛 멱등키로 보내면 409 IDEMPOTENCY_KEY_REUSED다.
export function issuePaymentToken(reservation: MockReservation) {
  return `mock-payment.${reservation.reservationId}.${nextMockId()}`
}

function holdResponse(reservation: MockReservation) {
  const confirmed = reservation.status === 'CONFIRMED'
  return ok(
    {
      requestId: reservation.requestId,
      reservationId: reservation.reservationId,
      reservationNo: reservation.reservationNo,
      scheduleId: reservation.scheduleId,
      seatIds: reservation.seatIds,
      status: reservation.status,
      holdExpiresAt: toKstIso(reservation.holdExpiresAtMillis),
      totalAmount: reservation.totalAmount,
      paymentToken: confirmed ? null : issuePaymentToken(reservation),
      paymentTokenExpiresAt: confirmed
        ? null
        : toKstIso(reservation.holdExpiresAtMillis - PAYMENT_TOKEN_MARGIN_MILLIS),
    },
    { status: 201, message: '선점되었습니다' },
  )
}

// 서버 SyncSeatHoldService.sortedDistinctSeatIds(:143-148): 형식 검증(양수 정수) 뒤 빈 배열·중복은 SEAT_COUNT_MISMATCH,
// 정렬한 목록이 응답과 멱등 본문 비교의 기준이다. 회차의 1인 최대 매수 검사는 같은 키 재요청 확인 뒤에 따로 한다(:85).
function parseSeatIds(value: unknown): number[] {
  if (!Array.isArray(value) || !value.every((id) => Number.isInteger(id) && id > 0)) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
  }
  const seatIds = [...new Set(value as number[])].sort((left, right) => left - right)
  if (seatIds.length !== value.length || seatIds.length < 1) {
    throw fail(400, 'SEAT_COUNT_MISMATCH', '선택한 좌석 수나 좌석이 올바르지 않습니다')
  }
  return seatIds
}

// 같은 키 재요청(contracts.md 3-2). 본문이 다르면 409, 아직 결제할 수 있으면 같은 결과, 시간이 지났으면 409다.
function replayHold(
  key: string,
  request: { userId: number; scheduleId: number; seatKey: string },
): Response | null {
  const previous = mockDb.holdRequests.get(key)
  if (previous === undefined) {
    return null
  }
  if (
    previous.userId !== request.userId ||
    previous.scheduleId !== request.scheduleId ||
    previous.seatKey !== request.seatKey
  ) {
    return fail(409, 'IDEMPOTENCY_KEY_REUSED', '같은 멱등키로 다른 요청을 보낼 수 없습니다')
  }
  const reservation = mockDb.reservations.get(previous.reservationId)!
  const payable =
    reservation.status === 'HELD' &&
    Date.now() < reservation.holdExpiresAtMillis - PAYMENT_TOKEN_MARGIN_MILLIS
  if (reservation.status === 'CONFIRMED' || payable) {
    return holdResponse(reservation)
  }
  return fail(409, 'RESERVATION_NOT_PAYABLE', '결제할 수 없는 예매입니다')
}

function heldSeatCount(userId: number, scheduleId: number) {
  let count = 0
  for (const reservation of mockDb.reservations.values()) {
    const active = reservation.status === 'HELD' || reservation.status === 'CONFIRMED'
    if (active && reservation.userId === userId && reservation.scheduleId === scheduleId) {
      count += reservation.seatIds.length
    }
  }
  return count
}

function concertOfSchedule(scheduleId: number) {
  const schedule = mockDb.schedules.find((candidate) => candidate.scheduleId === scheduleId)!
  const concert = mockDb.concerts.find((candidate) => candidate.concertId === schedule.concertId)!
  return { schedule, concert }
}

function ownReservation(request: Request, reservationId: number): MockReservation {
  const user = requireUser(request)
  const reservation = mockDb.reservations.get(reservationId)
  // 남의 예매와 없는 예매는 같은 404다
  if (reservation === undefined || reservation.userId !== user.userId) {
    throw fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
  }
  return reservation
}

export const reservationHandlers = [
  http.post('/api/v1/schedules/:scheduleId/reservations', async ({ request, params }) => {
    const scheduleId = Number(params.scheduleId)
    const user = requireUser(request)
    requireEntryToken(request, user.userId, scheduleId)
    const key = requireIdempotencyKey(request)
    const seatIds = parseSeatIds((await readBody(request)).seatIds)
    settleExpiredHolds()

    // 서버는 같은 키 재요청을 회차 조회·판매 기간 검사보다 먼저 본다(SyncSeatHoldService:79-82)
    const replayed = replayHold(key, {
      userId: user.userId,
      scheduleId,
      seatKey: seatIds.join(','),
    })
    if (replayed !== null) {
      return replayed
    }
    const schedule = requireSchedule(scheduleId)
    if (seatIds.length > schedule.maxPerUser) {
      return fail(400, 'SEAT_COUNT_MISMATCH', '선택한 좌석 수나 좌석이 올바르지 않습니다')
    }
    if (saleStatusOf(schedule) !== 'ON_SALE') {
      return fail(409, 'SALE_NOT_OPEN', '판매 중인 회차가 아닙니다')
    }

    const seats = mockDb.seats.filter(
      (seat) => seat.scheduleId === scheduleId && seatIds.includes(seat.seatId),
    )
    if (seats.length !== seatIds.length) {
      return fail(400, 'SEAT_COUNT_MISMATCH', '선택한 좌석 수나 좌석이 올바르지 않습니다')
    }
    if (heldSeatCount(user.userId, scheduleId) + seatIds.length > schedule.maxPerUser) {
      return fail(409, 'PURCHASE_LIMIT_EXCEEDED', '회차당 구매 가능 매수를 넘었습니다')
    }
    if (mockScenario().seatHold === 'seat-conflict' && !mockDb.seatConflictUsed) {
      // 다른 사람이 먼저 잡은 것처럼 첫 좌석을 팔린 상태로 만든다. 다시 조회하면 그 좌석만 빠져 있다
      mockDb.seatConflictUsed = true
      seats[0].available = false
    }
    if (seats.some((seat) => !seat.available)) {
      return fail(409, 'SEAT_ALREADY_HELD', '이미 선택된 좌석입니다')
    }

    seats.forEach((seat) => (seat.available = false))
    const reservationId = nextMockId()
    const reservation: MockReservation = {
      reservationId,
      reservationNo: `R20261102-${String(reservationId).padStart(6, '0')}`,
      requestId: key,
      userId: user.userId,
      scheduleId,
      seatIds,
      status: 'HELD',
      holdExpiresAtMillis: Date.now() + HOLD_MILLIS,
      totalAmount: seats.reduce((sum, seat) => sum + seat.price, 0),
      confirmAfterChecks: null,
    }
    mockDb.reservations.set(reservationId, reservation)
    mockDb.holdRequests.set(key, {
      userId: user.userId,
      scheduleId,
      seatKey: seatIds.join(','),
      reservationId,
    })
    if (mockScenario().seatHold === 'hold-unknown' && !mockDb.holdUnknownUsed) {
      // 서버는 선점을 마쳤지만 Gateway가 응답 시한을 넘겨 504를 준 경우. 코드는 실제 Gateway처럼 INTERNAL_SERVER_ERROR다
      mockDb.holdUnknownUsed = true
      return fail(504, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
    }
    return holdResponse(reservation)
  }),

  http.get('/api/v1/reservations/me', ({ request }) => {
    const user = requireUser(request)
    settleExpiredHolds()
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)

    const owned = [...mockDb.reservations.values()].filter(
      (reservation) => reservation.userId === user.userId,
    )
    owned.forEach(advanceConfirmation)
    const mine = owned
      .sort((left, right) => right.reservationId - left.reservationId)
      .map((reservation) => {
        const { schedule, concert } = concertOfSchedule(reservation.scheduleId)
        return {
          reservationId: reservation.reservationId,
          reservationNo: reservation.reservationNo,
          concertTitle: concert.title,
          startsAt: toKstIso(schedule.startsAtMillis),
          status: reservation.status,
          totalAmount: reservation.totalAmount,
        }
      })
    return ok({
      content: mine.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: mine.length,
    })
  }),

  http.get('/api/v1/reservations/:reservationId', ({ request, params }) => {
    const reservationId = Number(params.reservationId)
    // 서버는 숫자가 아니거나 0 이하인 ID를 400으로 거른다(ReservationController:47-49)
    if (!Number.isInteger(reservationId) || reservationId <= 0) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }
    settleExpiredHolds()
    const reservation = ownReservation(request, reservationId)
    advanceConfirmation(reservation)
    const seats = mockDb.seats.filter((seat) => reservation.seatIds.includes(seat.seatId))
    return ok({
      reservationId: reservation.reservationId,
      reservationNo: reservation.reservationNo,
      scheduleId: reservation.scheduleId,
      status: reservation.status,
      holdExpiresAt: toKstIso(reservation.holdExpiresAtMillis),
      totalAmount: reservation.totalAmount,
      seats: seats.map(({ seatId, section, row, number, grade, price }) => ({
        seatId,
        section,
        row,
        number,
        grade,
        price,
      })),
      // HELD이고 결제 가능 시간(만료 10초 전) 안일 때만 준다(ReservationService:47-55)
      paymentToken:
        reservation.status === 'HELD' &&
        Date.now() < reservation.holdExpiresAtMillis - PAYMENT_TOKEN_MARGIN_MILLIS
          ? issuePaymentToken(reservation)
          : null,
      tickets:
        reservation.status === 'CONFIRMED'
          ? reservation.seatIds.map((seatId) => ({
              ticketNo: `T${reservation.reservationId}-${seatId}`,
              seatId,
            }))
          : [],
    })
  }),
]
