import { http } from 'msw'
import { isoDateTime } from '@/api/envelope'
import { mockDb, nextMockId, type MockLottery } from '@/mocks/db'
import { requireAdmin } from '@/mocks/handlers/admin'
import { fail, ok, readBody } from '@/mocks/respond'

// 운영자 추첨 설정·수정(reservation-service AdminLotteryController, contracts-remaining 5절).
// 서버 근거는 origin/dev의 AdminLotteryController(:30-46), LotteryAdminService(:46-107), Lottery(:83-148),
// LotteryCreateRequest·LotteryUpdateRequest, LotteryResult(:24-30), DrawTimeWindow.

const MINUTE = 60_000
// 추첨은 회차 시작 1시간 전까지여야 한다(Lottery.MAX_SETTLE_AFTER_DRAW)
const MAX_SETTLE_AFTER_DRAW = 60 * MINUTE
const KST_OFFSET = 9 * 60 * MINUTE
const DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW = 'DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW'
const SOURCE_GRADES = ['VIP', 'R', 'S'] as const
type SourceGrade = (typeof SOURCE_GRADES)[number]

interface Settings {
  sourceGrade: SourceGrade
  seatCount: number
  waitlistCount: number
  maxPerEntry: number
  entryStartAtMillis: number
  entryEndAtMillis: number
  drawAtMillis: number
}

type RawField =
  | 'sourceGrade'
  | 'seatCount'
  | 'waitlistCount'
  | 'maxPerEntry'
  | 'entryStartAt'
  | 'entryEndAt'
  | 'drawAt'
const FIELDS: RawField[] = [
  'sourceGrade',
  'seatCount',
  'waitlistCount',
  'maxPerEntry',
  'entryStartAt',
  'entryEndAt',
  'drawAt',
]

// Instant라 `Z`로 낸다. 서버는 값에 밀리초가 있으면 소수부까지 준다
function toInstant(millis: number): string {
  return new Date(millis).toISOString()
}

// DrawTimeWindow.contains: 한국 시간 03:00 이상 05:00 미만
function inLowTrafficWindow(drawAtMillis: number): boolean {
  const hour = new Date(drawAtMillis + KST_OFFSET).getUTCHours()
  return hour >= 3 && hour < 5
}

function lotteryResponse(lottery: MockLottery) {
  return {
    lotteryId: lottery.lotteryId,
    scheduleId: lottery.scheduleId,
    status: lottery.status,
    sourceGrade: lottery.sourceGrade,
    seatCount: lottery.seatCount,
    waitlistCount: lottery.waitlistCount,
    maxPerEntry: lottery.maxPerEntry,
    entryStartAt: toInstant(lottery.entryStartAtMillis),
    entryEndAt: toInstant(lottery.entryEndAtMillis),
    drawAt: toInstant(lottery.drawAtMillis),
    warnings: inLowTrafficWindow(lottery.drawAtMillis) ? [] : [DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW],
  }
}

function idOf(value: string | readonly string[] | undefined): number {
  const id = Number(value)
  // 숫자가 아닌 경로 값은 Spring이 400으로 거른다
  if (!Number.isInteger(id)) {
    throw unreadable()
  }
  return id
}

const unreadable = () => fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')

// Jackson이 읽을 수 없는 값(모르는 등급, 숫자가 아닌 값, 시각이 아닌 값)은 칸별 오류 없는 400이다.
// 값이 없거나 null이면 읽기는 되고 Bean Validation이 막는다
function readFields(body: Record<string, unknown>): Partial<Record<RawField, unknown>> {
  const read: Partial<Record<RawField, unknown>> = {}
  for (const field of FIELDS) {
    const value = body[field]
    if (value === undefined || value === null) {
      continue
    }
    if (field === 'sourceGrade') {
      if (typeof value !== 'string' || ![...SOURCE_GRADES, 'LOTTERY'].includes(value)) {
        throw unreadable()
      }
    } else if (field === 'seatCount' || field === 'waitlistCount' || field === 'maxPerEntry') {
      if (typeof value !== 'number' || !Number.isInteger(value)) {
        throw unreadable()
      }
    } else if (typeof value !== 'string' || !isoDateTime.safeParse(value).success) {
      throw unreadable()
    }
    read[field] = value
  }
  return read
}

interface FieldError {
  field: string
  reason: string
}

// 설정은 모든 칸이 필수(@NotNull), 수정은 보낸 칸만 검사한다
function constraintErrors(read: Partial<Record<RawField, unknown>>, required: boolean) {
  const errors: FieldError[] = []
  for (const field of FIELDS) {
    const value = read[field]
    if (value === undefined) {
      if (required) {
        errors.push({ field, reason: '널이어서는 안됩니다' })
      }
      continue
    }
    if (field === 'seatCount' && (value as number) <= 0) {
      errors.push({ field, reason: '0보다 커야 합니다' })
    }
    if (field === 'waitlistCount' && (value as number) < 0) {
      errors.push({ field, reason: '0 이상이어야 합니다' })
    }
    if (field === 'maxPerEntry' && (value as number) < 1) {
      errors.push({ field, reason: '1 이상이어야 합니다' })
    }
    if (field === 'maxPerEntry' && (value as number) > 2) {
      errors.push({ field, reason: '2 이하여야 합니다' })
    }
  }
  return errors
}

// LotterySettings.validate: 값 사이 규칙은 칸별 오류 없는 400이다
function validateSettings(settings: Settings, startsAtMillis: number) {
  if (
    (settings.sourceGrade as string) === 'LOTTERY' ||
    settings.seatCount < 1 ||
    settings.waitlistCount < 0 ||
    settings.maxPerEntry < 1 ||
    settings.maxPerEntry > 2 ||
    settings.entryStartAtMillis >= settings.entryEndAtMillis ||
    settings.entryEndAtMillis > settings.drawAtMillis ||
    settings.drawAtMillis + MAX_SETTLE_AFTER_DRAW > startsAtMillis
  ) {
    throw unreadable()
  }
}

// Lottery.requireBeforeSetupDeadline: 일반 오픈이나 응모 시작이 지났으면 막는다
function requireBeforeSetupDeadline(openAtMillis: number, entryStartAtMillis: number) {
  const now = Date.now()
  if (now >= openAtMillis || now >= entryStartAtMillis) {
    throw fail(
      409,
      'LOTTERY_SETUP_CLOSED',
      '일반 예매 오픈이나 응모 시작이 지나 추첨을 설정할 수 없습니다',
    )
  }
}

function scheduleOf(scheduleId: number) {
  const schedule = mockDb.schedules.find((candidate) => candidate.scheduleId === scheduleId)
  if (schedule === undefined) {
    throw fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
  }
  return schedule
}

const seatsNotEnough = () =>
  fail(409, 'LOTTERY_SEATS_NOT_ENOUGH', '추첨석으로 뗄 수 있는 좌석이 모자랍니다')

// JpaScheduleSeatRepository.updateGradeToLottery: 그 등급의 빈 좌석을 ID 순으로 count개 고른다.
// `releasing`은 이번 요청에서 풀리는 이 추첨의 추첨석이라 다시 고를 수 있다. 모자라면 null이다
function pickSeats(
  scheduleId: number,
  grade: SourceGrade,
  count: number,
  releasing: ReadonlySet<number> = new Set(),
  ownLottery?: MockLottery,
): number[] | null {
  const taken = new Set(
    mockDb.lotteries
      .filter((lottery) => lottery !== ownLottery && lottery.scheduleId === scheduleId)
      .flatMap((lottery) => lottery.convertedSeatIds),
  )
  const seats = mockDb.seats
    .filter(
      (seat) =>
        seat.scheduleId === scheduleId &&
        seat.grade === grade &&
        (seat.available || releasing.has(seat.seatId)) &&
        !taken.has(seat.seatId),
    )
    .sort((left, right) => left.seatId - right.seatId)
    .slice(0, count)
  return seats.length < count ? null : seats.map((seat) => seat.seatId)
}

// 목의 좌석 번호는 회차끼리 겹칠 수 있어(운영자 좌석 만들기가 번호를 이어 붙인다) 회차와 함께 찾는다
function setAvailable(scheduleId: number, seatIds: number[], available: boolean) {
  for (const seatId of seatIds) {
    const seat = mockDb.seats.find(
      (candidate) => candidate.scheduleId === scheduleId && candidate.seatId === seatId,
    )
    if (seat !== undefined) {
      seat.available = available
    }
  }
}

export const adminLotteryHandlers = [
  // AdminLotteryController:30-37
  http.post('/api/v1/admin/schedules/:scheduleId/lotteries', async ({ request, params }) => {
    requireAdmin(request)
    const scheduleId = idOf(params.scheduleId)
    const read = readFields(await readBody(request))
    const errors = constraintErrors(read, true)
    if (errors.length > 0) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', { errors })
    }
    const settings: Settings = {
      sourceGrade: read.sourceGrade as SourceGrade,
      seatCount: read.seatCount as number,
      waitlistCount: read.waitlistCount as number,
      maxPerEntry: read.maxPerEntry as number,
      entryStartAtMillis: Date.parse(read.entryStartAt as string),
      entryEndAtMillis: Date.parse(read.entryEndAt as string),
      drawAtMillis: Date.parse(read.drawAt as string),
    }

    // LotteryAdminService.create: 회차 → 이미 있는가 → Lottery.create(값 사이 규칙 → 설정 마감) → 좌석 전환
    const schedule = scheduleOf(scheduleId)
    if (mockDb.lotteries.some((lottery) => lottery.scheduleId === scheduleId)) {
      return fail(409, 'LOTTERY_ALREADY_EXISTS', '이미 추첨을 설정한 회차입니다')
    }
    validateSettings(settings, schedule.startsAtMillis)
    requireBeforeSetupDeadline(schedule.openAtMillis, settings.entryStartAtMillis)
    const seatIds = pickSeats(scheduleId, settings.sourceGrade, settings.seatCount)
    if (seatIds === null) {
      return seatsNotEnough()
    }

    setAvailable(scheduleId, seatIds, false)
    const lottery: MockLottery = {
      lotteryId: nextMockId(),
      scheduleId,
      status: 'OPEN',
      seatCount: settings.seatCount,
      entryStartAtMillis: settings.entryStartAtMillis,
      entryEndAtMillis: settings.entryEndAtMillis,
      drawAtMillis: settings.drawAtMillis,
      maxPerEntry: settings.maxPerEntry,
      sourceGrade: settings.sourceGrade,
      waitlistCount: settings.waitlistCount,
      convertedSeatIds: seatIds,
    }
    mockDb.lotteries.push(lottery)
    return ok(lotteryResponse(lottery), { status: 201, message: '추첨을 설정했습니다' })
  }),

  // AdminLotteryController:40-46, LotteryAdminService.update:63-90
  http.patch('/api/v1/admin/lotteries/:lotteryId', async ({ request, params }) => {
    requireAdmin(request)
    const lotteryId = idOf(params.lotteryId)
    const read = readFields(await readBody(request))
    const errors = constraintErrors(read, false)
    if (errors.length > 0) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', { errors })
    }
    const lottery = mockDb.lotteries.find((candidate) => candidate.lotteryId === lotteryId)
    if (lottery === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    const schedule = scheduleOf(lottery.scheduleId)

    const before: Settings = {
      sourceGrade: lottery.sourceGrade,
      seatCount: lottery.seatCount,
      waitlistCount: lottery.waitlistCount,
      maxPerEntry: lottery.maxPerEntry,
      entryStartAtMillis: lottery.entryStartAtMillis,
      entryEndAtMillis: lottery.entryEndAtMillis,
      drawAtMillis: lottery.drawAtMillis,
    }
    const millisOr = (value: unknown, fallback: number) =>
      value === undefined ? fallback : Date.parse(value as string)
    const after: Settings = {
      sourceGrade: (read.sourceGrade as SourceGrade | undefined) ?? before.sourceGrade,
      seatCount: (read.seatCount as number | undefined) ?? before.seatCount,
      waitlistCount: (read.waitlistCount as number | undefined) ?? before.waitlistCount,
      maxPerEntry: (read.maxPerEntry as number | undefined) ?? before.maxPerEntry,
      entryStartAtMillis: millisOr(read.entryStartAt, before.entryStartAtMillis),
      entryEndAtMillis: millisOr(read.entryEndAt, before.entryEndAtMillis),
      drawAtMillis: millisOr(read.drawAt, before.drawAtMillis),
    }
    // Lottery.update: 지금 값으로 시점 규칙 → 합친 값의 값 사이 규칙 → 합친 값의 시점 규칙
    requireBeforeSetupDeadline(schedule.openAtMillis, before.entryStartAtMillis)
    validateSettings(after, schedule.startsAtMillis)
    requireBeforeSetupDeadline(schedule.openAtMillis, after.entryStartAtMillis)

    // adjustSeats: 등급이 바뀌면 전부 되돌리고 새 등급에서 다시 떼고, 같은 등급이면 차이만큼 늘리거나 줄인다.
    // 좌석을 만들지 않은 시드 추첨은 되돌릴 좌석이 없어 줄이는 쪽은 그대로 통과한다
    const held = lottery.convertedSeatIds
    let converted = held
    if (before.sourceGrade !== after.sourceGrade) {
      const picked = pickSeats(
        lottery.scheduleId,
        after.sourceGrade,
        after.seatCount,
        new Set(held),
        lottery,
      )
      if (picked === null) {
        return seatsNotEnough()
      }
      converted = picked
    } else if (after.seatCount > before.seatCount) {
      const more = pickSeats(
        lottery.scheduleId,
        after.sourceGrade,
        after.seatCount - before.seatCount,
      )
      if (more === null) {
        return seatsNotEnough()
      }
      converted = [...held, ...more]
    } else if (after.seatCount < before.seatCount) {
      converted = held.slice(0, Math.max(0, held.length - (before.seatCount - after.seatCount)))
    }
    setAvailable(
      lottery.scheduleId,
      held.filter((seatId) => !converted.includes(seatId)),
      true,
    )
    setAvailable(lottery.scheduleId, converted, false)

    Object.assign(lottery, {
      sourceGrade: after.sourceGrade,
      seatCount: after.seatCount,
      waitlistCount: after.waitlistCount,
      maxPerEntry: after.maxPerEntry,
      entryStartAtMillis: after.entryStartAtMillis,
      entryEndAtMillis: after.entryEndAtMillis,
      drawAtMillis: after.drawAtMillis,
      convertedSeatIds: converted,
    })
    return ok(lotteryResponse(lottery), { message: '추첨 설정을 고쳤습니다' })
  }),
]
