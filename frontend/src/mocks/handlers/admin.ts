import { http } from 'msw'
import { isoDateTime } from '@/api/envelope'
import { createSeats, mockDb, nextMockId, type MockUser } from '@/mocks/db'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'

// 운영자 등록 API(SA 11-5, reservation-service AdminConcertController·AdminScheduleController).
// ADMIN이 아니면 Gateway가 403 FORBIDDEN을 준다. 검증 규칙은 서버의 요청 DTO와 같다.

const SMALLINT_MAX = 32767
const DEFAULT_MAX_PER_USER = 4
const SEAT_GRADES = ['VIP', 'R', 'S', 'LOTTERY']
// 목 좌석은 작게 만든다. 등급마다 구역 하나, 2열 × 10번
const SECTION_BY_GRADE: Record<string, string> = { VIP: 'A', R: 'B', S: 'C', LOTTERY: 'D' }
const MOCK_ROWS = 2
const MOCK_PER_ROW = 10

function requireAdmin(request: Request): MockUser {
  const user = requireUser(request)
  if (user.role !== 'ADMIN') {
    throw fail(403, 'FORBIDDEN', '접근 권한이 없습니다')
  }
  return user
}

function validationFailed(field?: string, reason?: string) {
  return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
    errors: field === undefined ? undefined : [{ field, reason: reason ?? '올바르지 않습니다' }],
  })
}

function isIntegerInRange(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max
}

// Instant(ISO-8601) 문자열만 받는다. 서버(Jackson Instant)는 오프셋(Z, ±hh:mm)이 없는 값을 400으로 거절한다.
// 시각 밀리초를 돌려주고, 아니면 null이다
function parseInstant(value: unknown): number | null {
  if (typeof value !== 'string' || !isoDateTime.safeParse(value).success) {
    return null
  }
  const millis = Date.parse(value)
  return Number.isNaN(millis) ? null : millis
}

export const adminHandlers = [
  http.post('/api/v1/admin/concerts', async ({ request }) => {
    requireAdmin(request)
    const body = await readBody(request)
    const { title, description } = body
    if (typeof title !== 'string' || title.trim() === '') {
      return validationFailed('title', '공백일 수 없습니다')
    }
    if (title.length > 200) {
      return validationFailed('title', '200자 이하여야 합니다')
    }
    if (description !== undefined && description !== null && typeof description !== 'string') {
      return validationFailed('description', '문자열이어야 합니다')
    }
    const concertId = nextMockId()
    // 서버(Concert.description)는 설명을 trim하지 않고 그대로 저장한다. 없음·빈 문자열만 null이다
    const storedDescription =
      typeof description === 'string' && description !== '' ? description : null
    mockDb.concerts.push({ concertId, title, description: storedDescription })
    return ok({ concertId }, { status: 201 })
  }),

  http.post('/api/v1/admin/concerts/:concertId/schedules', async ({ request, params }) => {
    requireAdmin(request)
    const body = await readBody(request)
    const { venueId, maxPerUser, holdMinutes } = body
    const startsAtMillis = parseInstant(body.startsAt)
    const openAtMillis = parseInstant(body.openAt)
    if (!isIntegerInRange(venueId, 1, Number.MAX_SAFE_INTEGER)) {
      return validationFailed('venueId', '양수여야 합니다')
    }
    if (startsAtMillis === null) {
      return validationFailed('startsAt', '시각 형식이어야 합니다')
    }
    if (openAtMillis === null) {
      return validationFailed('openAt', '시각 형식이어야 합니다')
    }
    for (const [field, value] of [
      ['maxPerUser', maxPerUser],
      ['holdMinutes', holdMinutes],
    ] as const) {
      if (value !== undefined && value !== null && !isIntegerInRange(value, 1, SMALLINT_MAX)) {
        return validationFailed(field, `1 이상 ${SMALLINT_MAX} 이하여야 합니다`)
      }
    }

    // 서버는 공연·공연장 404를 먼저 보고, Schedule.create에서 오픈 순서를 검사한다
    const concert = mockDb.concerts.find(
      (candidate) => candidate.concertId === Number(params.concertId),
    )
    const venue = mockDb.venues.find((candidate) => candidate.venueId === venueId)
    if (concert === undefined || venue === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    if (openAtMillis >= startsAtMillis) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }

    const scheduleId = nextMockId()
    mockDb.schedules.push({
      scheduleId,
      concertId: concert.concertId,
      venueName: venue.name,
      startsAtMillis,
      openAtMillis,
      maxPerUser: typeof maxPerUser === 'number' ? maxPerUser : DEFAULT_MAX_PER_USER,
    })
    return ok({ scheduleId }, { status: 201 })
  }),

  http.post('/api/v1/admin/schedules/:scheduleId/seats', async ({ request, params }) => {
    requireAdmin(request)
    const body = await readBody(request)
    const prices = body.prices
    if (!Array.isArray(prices) || prices.length === 0) {
      return validationFailed('prices', '비어 있을 수 없습니다')
    }
    const priceByGrade = new Map<string, number>()
    for (const item of prices as Record<string, unknown>[]) {
      const grade = item?.grade
      const price = item?.price
      if (typeof grade !== 'string' || !SEAT_GRADES.includes(grade)) {
        return validationFailed('prices.grade', 'VIP, R, S, LOTTERY 중 하나여야 합니다')
      }
      if (!isIntegerInRange(price, 1, 999_999_999_999_999)) {
        return validationFailed('prices.price', '양수여야 합니다')
      }
      if (priceByGrade.has(grade)) {
        return validationFailed('prices', '같은 등급이 두 번 있을 수 없습니다')
      }
      priceByGrade.set(grade, price)
    }

    const scheduleId = Number(params.scheduleId)
    const schedule = mockDb.schedules.find((candidate) => candidate.scheduleId === scheduleId)
    if (schedule === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    if (mockDb.seats.some((seat) => seat.scheduleId === scheduleId)) {
      return fail(409, 'SCHEDULE_SEATS_ALREADY_CREATED', '이미 좌석을 만든 회차입니다')
    }
    // 목 회차는 공연장 이름으로 공연장을 찾는다(기존 시드 회차의 공연장은 표에 없을 수 있다)
    const venue = mockDb.venues.find((candidate) => candidate.name === schedule.venueName)
    const venueGrades = venue?.grades ?? []
    const sameGrades =
      venueGrades.length === priceByGrade.size &&
      venueGrades.every((grade) => priceByGrade.has(grade))
    if (!sameGrades) {
      // 서버는 BusinessException이라 칸별 errors가 없다
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }

    let createdSeats = 0
    for (const [grade, price] of priceByGrade) {
      const firstSeatId = mockDb.nextId + 1
      mockDb.nextId += MOCK_ROWS * MOCK_PER_ROW
      const seats = createSeats({
        scheduleId,
        firstSeatId,
        section: SECTION_BY_GRADE[grade],
        grade,
        price,
        rows: MOCK_ROWS,
        perRow: MOCK_PER_ROW,
      })
      mockDb.seats.push(...seats)
      createdSeats += seats.length
    }
    return ok({ scheduleId, createdSeats }, { status: 201 })
  }),
]
