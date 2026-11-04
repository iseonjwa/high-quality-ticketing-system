import { http } from 'msw'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import {
  mockDb,
  nextMockId,
  releaseSeats,
  type MockLottery,
  type MockLotteryEntry,
  type MockReservation,
} from '@/mocks/db'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'
import { mockScenario } from '@/mocks/scenario'

// 추첨 정보·응모·취소·내 결과(reservation-service LotteryController, `docs/design/lottery/`, contracts-remaining 4절).
// 서버 근거는 origin/dev의 LotteryController(:40-74), LotteryEntryService(:33-89), LotteryEntryDtos(:15-45),
// SpringDataLotteryEntryRepository(:25-48). 운영자 추첨 설정·추첨 실행은 ISE-124가 맡는다.

const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
// 당첨 예매의 선점은 30분이다(SystemSeatHoldService:42)
const LOTTERY_HOLD_MILLIS = 30 * 60_000
const MOCK_WAITLIST_RANK = 3

// Instant라 `Z`로 낸다(LotteryInfoResponse). 일반 예매의 `+09:00`과 다르다
function toInstant(millis: number): string {
  return new Date(millis).toISOString().slice(0, 19) + 'Z'
}

function lotteryInfo(lottery: MockLottery) {
  return {
    lotteryId: lottery.lotteryId,
    scheduleId: lottery.scheduleId,
    status: lottery.status,
    seatCount: lottery.seatCount,
    entryStartAt: toInstant(lottery.entryStartAtMillis),
    entryEndAt: toInstant(lottery.entryEndAtMillis),
    drawAt: toInstant(lottery.drawAtMillis),
    // 서버는 minPerEntry 1, 연석 보장 없음, 재응모 불가, 빌링키 필요를 고정으로 준다(LotteryInfoResponse:34)
    rules: {
      minPerEntry: 1,
      maxPerEntry: lottery.maxPerEntry,
      adjacentSeatsGuaranteed: false,
      reentryAllowed: false,
      billingKeyRequired: true,
    },
  }
}

function entryResponse(entry: MockLotteryEntry) {
  return {
    entryId: entry.entryId,
    lotteryId: entry.lotteryId,
    status: entry.status,
    quantity: entry.quantity,
    drawRank: entry.drawRank,
    reservationId: entry.reservationId,
    failReason: entry.failReason,
  }
}

function lotteryIdOf(params: Record<string, string | readonly string[] | undefined>): number {
  const lotteryId = Number(params.lotteryId)
  // 숫자가 아닌 경로 값은 Spring이 400으로 거른다
  if (!Number.isInteger(lotteryId)) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
  }
  return lotteryId
}

function isOpenForEntry(lottery: MockLottery, nowMillis: number): boolean {
  return (
    lottery.status === 'OPEN' &&
    nowMillis >= lottery.entryStartAtMillis &&
    nowMillis < lottery.entryEndAtMillis
  )
}

function entryOf(lotteryId: number, userId: number) {
  return mockDb.lotteryEntries.find(
    (entry) => entry.lotteryId === lotteryId && entry.userId === userId,
  )
}

// EntryRequest: @NotNull @Min(1) @Max(2) Integer quantity (LotteryEntryDtos:15)
function parseQuantity(body: Record<string, unknown>): number {
  const quantity = body.quantity
  if (quantity === undefined || quantity === null) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
      errors: [{ field: 'quantity', reason: 'null일 수 없습니다' }],
    })
  }
  if (typeof quantity !== 'number' || !Number.isInteger(quantity)) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
  }
  if (quantity < 1) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
      errors: [{ field: 'quantity', reason: '1 이상이어야 합니다' }],
    })
  }
  if (quantity > 2) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
      errors: [{ field: 'quantity', reason: '2 이하여야 합니다' }],
    })
  }
  return quantity
}

// 3번 추첨의 데모 응모는 조회할 때 시나리오에 따라 결과가 정해진다. 기본은 처음 조회 WON(좌석 배정, 결제 대기),
// 다음 조회부터 PAID(자동결제 승인, 예매 확정). 서버에서는 추첨·배정·자동결제 스케줄러가 시간을 두고 하는 일이다.
function advanceSeededEntry(entry: MockLotteryEntry) {
  if (entry.seedStage === 'settled') {
    return
  }
  const scenario = mockScenario().lottery
  if (entry.seedStage === 'pending') {
    if (scenario === 'waitlisted') {
      entry.status = 'WAITLISTED'
      entry.drawRank = MOCK_WAITLIST_RANK
      entry.seedStage = 'settled'
    } else if (scenario === 'lost') {
      entry.status = 'LOST'
      entry.drawRank = null
      entry.seedStage = 'settled'
    } else {
      entry.status = 'WON'
      entry.drawRank = 1
      entry.reservationId = holdSeatsForWinner(entry).reservationId
      entry.seedStage = 'shown'
    }
    return
  }
  const reservation = mockDb.reservations.get(entry.reservationId!)!
  if (scenario === 'failed') {
    // 자동결제가 거절돼 선점을 풀고 다음 순번으로 넘긴다
    entry.status = 'FAILED'
    entry.failReason = 'BILLING_KEY_MISSING'
    reservation.status = 'RELEASED'
    releaseSeats(reservation.seatIds)
  } else {
    entry.status = 'PAID'
    reservation.status = 'CONFIRMED'
  }
  entry.seedStage = 'settled'
}

// 당첨 예매(HELD)를 만든다. 일반 예매 테스트가 쓰는 앞쪽 좌석을 피해 회차의 뒤쪽 빈 좌석을 잡는다
function holdSeatsForWinner(entry: MockLotteryEntry): MockReservation {
  const lottery = mockDb.lotteries.find((candidate) => candidate.lotteryId === entry.lotteryId)!
  const seats = mockDb.seats
    .filter((seat) => seat.scheduleId === lottery.scheduleId && seat.available)
    .reverse()
    .slice(0, entry.quantity)
  seats.forEach((seat) => (seat.available = false))
  const reservationId = nextMockId()
  const reservation: MockReservation = {
    reservationId,
    reservationNo: `R20261102-${String(reservationId).padStart(6, '0')}`,
    requestId: newIdempotencyKey(),
    userId: entry.userId,
    scheduleId: lottery.scheduleId,
    seatIds: seats.map((seat) => seat.seatId),
    status: 'HELD',
    holdExpiresAtMillis: Date.now() + LOTTERY_HOLD_MILLIS,
    totalAmount: seats.reduce((sum, seat) => sum + seat.price, 0),
    confirmAfterChecks: null,
  }
  mockDb.reservations.set(reservationId, reservation)
  return reservation
}

export const lotteryHandlers = [
  // 공개. 토큰을 보내도 쓰지 않는다(LotteryController:69-74)
  http.get('/api/v1/lotteries/:lotteryId', ({ params }) => {
    const lotteryId = lotteryIdOf(params)
    const lottery = mockDb.lotteries.find((candidate) => candidate.lotteryId === lotteryId)
    if (lottery === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    return ok(lotteryInfo(lottery), { message: '추첨 정보입니다' })
  }),

  // LotteryController:40-48, LotteryEntryService:33-44·79-89
  http.post('/api/v1/lotteries/:lotteryId/entries', async ({ request, params }) => {
    const user = requireUser(request)
    const lotteryId = lotteryIdOf(params)
    // 인자 해석 순서: Idempotency-Key 헤더 → 본문 검증 → (메서드 안) 키 형식 확인
    const key = request.headers.get('Idempotency-Key')
    if (key === null) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }
    const quantity = parseQuantity(await readBody(request))
    if (!UUID_FORMAT.test(key)) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }

    // 같은 키 확인이 기간·존재 검사보다 먼저다. 키는 사용자별이 아니라 전역이다
    const replay = mockDb.lotteryEntries.find((entry) => entry.requestId === key)
    if (replay !== undefined) {
      const same =
        replay.userId === user.userId &&
        replay.lotteryId === lotteryId &&
        replay.quantity === quantity
      return same
        ? ok(entryResponse(replay), { status: 201, message: '응모했습니다' })
        : fail(409, 'IDEMPOTENCY_KEY_REUSED', '같은 멱등키로 다른 요청을 보낼 수 없습니다')
    }

    const lottery = mockDb.lotteries.find((candidate) => candidate.lotteryId === lotteryId)
    const closedByScenario = mockScenario().lottery === 'closed'
    const insertable =
      lottery !== undefined &&
      isOpenForEntry(lottery, Date.now()) &&
      quantity <= lottery.maxPerEntry &&
      entryOf(lotteryId, user.userId) === undefined &&
      !closedByScenario
    if (!insertable) {
      // rejectReason 순서(LotteryEntryService:79-89): 없는 추첨 → 이미 응모 → 매수 초과 → 기간 밖
      if (lottery === undefined) {
        return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
      }
      if (entryOf(lotteryId, user.userId) !== undefined) {
        return fail(
          409,
          'LOTTERY_ALREADY_ENTERED',
          '이미 응모한 추첨입니다. 취소한 뒤에도 다시 응모할 수 없습니다',
        )
      }
      if (quantity > lottery.maxPerEntry && !closedByScenario) {
        return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
      }
      return fail(409, 'LOTTERY_NOT_OPEN', '응모 기간이 아닙니다')
    }

    const entry: MockLotteryEntry = {
      entryId: nextMockId(),
      lotteryId,
      requestId: key,
      userId: user.userId,
      quantity,
      status: 'ENTERED',
      drawRank: null,
      reservationId: null,
      failReason: null,
      seedStage: 'settled',
    }
    mockDb.lotteryEntries.push(entry)
    if (mockScenario().lottery === 'unknown' && !mockDb.lotteryUnknownUsed) {
      // 서버는 응모를 마쳤지만 Gateway가 응답 시한을 넘겨 504를 준 경우
      mockDb.lotteryUnknownUsed = true
      return fail(504, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
    }
    return ok(entryResponse(entry), { status: 201, message: '응모했습니다' })
  }),

  // LotteryController:51-57, LotteryEntryService:47-56, SpringDataLotteryEntryRepository:39-48
  http.delete('/api/v1/lotteries/:lotteryId/entries/me', ({ request, params }) => {
    const user = requireUser(request)
    const lotteryId = lotteryIdOf(params)
    const entry = entryOf(lotteryId, user.userId)
    if (entry === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    const lottery = mockDb.lotteries.find((candidate) => candidate.lotteryId === lotteryId)
    const cancelable =
      entry.status === 'ENTERED' &&
      lottery?.status === 'OPEN' &&
      Date.now() < lottery.entryEndAtMillis
    if (cancelable) {
      entry.status = 'CANCELED'
    } else if (entry.status !== 'CANCELED') {
      return fail(409, 'LOTTERY_NOT_OPEN', '응모 기간이 아닙니다')
    }
    return ok(entryResponse(entry), { message: '응모를 취소했습니다' })
  }),

  // LotteryController:60-66. 취소한 응모도 그대로 준다
  http.get('/api/v1/lotteries/:lotteryId/entries/me', ({ request, params }) => {
    const user = requireUser(request)
    const lotteryId = lotteryIdOf(params)
    const entry = entryOf(lotteryId, user.userId)
    if (entry === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    advanceSeededEntry(entry)
    return ok(entryResponse(entry), { message: '응모 결과입니다' })
  }),
]
