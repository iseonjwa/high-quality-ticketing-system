import type { LotteryCreateInput } from '@/api/adminLotteries'
import { createSeats, mockDb, nextMockId } from '@/mocks/db'

// 운영자 추첨 테스트가 쓰는 준비 단계. 시각은 모두 지금 기준이다.

export const MINUTE = 60_000
export const HOUR = 60 * MINUTE
export const DAY = 24 * HOUR
const KST_OFFSET = 9 * HOUR

// 지금부터 days일 뒤 한국 시각 hour시 minute분(밀리초)
export function kstTimeAfterDays(days: number, hour: number, minute = 0): number {
  const kstDay = Math.floor((Date.now() + KST_OFFSET) / DAY) + days
  return kstDay * DAY + hour * HOUR + minute * MINUTE - KST_OFFSET
}

export const iso = (millis: number) => new Date(millis).toISOString()

// datetime-local 칸에 넣는 한국 시각 글자("2026-11-20T10:00")
export const kstLocal = (millis: number) => new Date(millis + KST_OFFSET).toISOString().slice(0, 16)

interface SeedScheduleOptions {
  // 예매 오픈(지금 기준 밀리초). 기본은 이틀 뒤라 추첨을 설정할 수 있다
  openAfter?: number
  // 공연 시작. 기본은 30일 뒤
  startsAfter?: number
  // 좌석을 만든다. 기본은 VIP 20석(2열 × 10번)
  seats?: boolean
}

// 공연 7에 새 회차를 만든다. 추첨 설정 테스트의 대상이다
export function seedScheduleForLottery(options: SeedScheduleOptions = {}) {
  const { openAfter = 2 * DAY, startsAfter = 30 * DAY, seats = true } = options
  const now = Date.now()
  const scheduleId = nextMockId()
  mockDb.schedules.push({
    scheduleId,
    concertId: 7,
    venueName: 'KSPO DOME',
    startsAtMillis: now + startsAfter,
    openAtMillis: now + openAfter,
    maxPerUser: 4,
  })
  if (seats) {
    // 시드 좌석(1001~)과 겹치지 않는 번호
    const firstSeatId = 800_000 + scheduleId * 100
    mockDb.seats.push(
      ...createSeats({
        scheduleId,
        firstSeatId,
        section: 'A',
        grade: 'VIP',
        price: 165000,
        rows: 2,
        perRow: 10,
      }),
    )
  }
  return scheduleId
}

// 이틀 뒤 응모 시작, 사흘 뒤 마감, 나흘 뒤 새벽 4시(한국) 추첨. 권장 시간대 안이라 경고가 없다
export function lotteryInput(overrides: Partial<LotteryCreateInput> = {}): LotteryCreateInput {
  // 응답의 Z 시각과 같은 값이 되도록 초 단위로 맞춘다
  const now = Math.floor(Date.now() / 1000) * 1000
  return {
    sourceGrade: 'VIP',
    seatCount: 5,
    waitlistCount: 2,
    maxPerEntry: 2,
    entryStartAt: iso(now + DAY),
    entryEndAt: iso(now + 2 * DAY - HOUR),
    drawAt: iso(kstTimeAfterDays(4, 4)),
    ...overrides,
  }
}

export function seatsOf(scheduleId: number) {
  return mockDb.seats.filter((seat) => seat.scheduleId === scheduleId)
}
