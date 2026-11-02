import { http } from 'msw'
import { mockDb, settleExpiredHolds, toKstIso, type MockSchedule } from '@/mocks/db'
import { fail, ok, requireEntryToken, requireSchedule, requireUser } from '@/mocks/respond'

// 공연·좌석 조회(SA 11-5). 백엔드 미구현(ISE-99)이라 SA 계약대로 응답한다.

export function saleStatusOf(schedule: MockSchedule, nowMillis = Date.now()) {
  if (nowMillis < schedule.openAtMillis) {
    return 'BEFORE_OPEN'
  }
  return nowMillis < schedule.startsAtMillis ? 'ON_SALE' : 'CLOSED'
}

function schedulesOf(concertId: number) {
  return mockDb.schedules
    .filter((schedule) => schedule.concertId === concertId)
    .sort((left, right) => left.startsAtMillis - right.startsAtMillis)
}

export const concertHandlers = [
  http.get('/api/v1/concerts', ({ request }) => {
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const now = Date.now()

    const summaries = mockDb.concerts.map((concert) => {
      const next = schedulesOf(concert.concertId).find((schedule) => schedule.startsAtMillis > now)
      return {
        concertId: concert.concertId,
        title: concert.title,
        nextStartsAt: next ? toKstIso(next.startsAtMillis) : null,
        nextOpenAt: next ? toKstIso(next.openAtMillis) : null,
      }
    })
    return ok({
      content: summaries.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: summaries.length,
    })
  }),

  http.get('/api/v1/concerts/:concertId', ({ params }) => {
    const concert = mockDb.concerts.find(
      (candidate) => candidate.concertId === Number(params.concertId),
    )
    if (concert === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    const now = Date.now()
    return ok({
      concertId: concert.concertId,
      title: concert.title,
      description: concert.description,
      serverTime: toKstIso(now),
      schedules: schedulesOf(concert.concertId).map((schedule) => ({
        scheduleId: schedule.scheduleId,
        venueName: schedule.venueName,
        startsAt: toKstIso(schedule.startsAtMillis),
        openAt: toKstIso(schedule.openAtMillis),
        maxPerUser: schedule.maxPerUser,
        saleStatus: saleStatusOf(schedule, now),
      })),
    })
  }),

  http.get('/api/v1/schedules/:scheduleId/seats', ({ request, params }) => {
    const scheduleId = Number(params.scheduleId)
    const user = requireUser(request)
    requireEntryToken(request, user.userId, scheduleId)
    requireSchedule(scheduleId)
    settleExpiredHolds()

    const sections = new Map<
      string,
      { section: string; grade: string; price: number; seats: unknown[] }
    >()
    for (const seat of mockDb.seats.filter((candidate) => candidate.scheduleId === scheduleId)) {
      const section = sections.get(seat.section) ?? {
        section: seat.section,
        grade: seat.grade,
        price: seat.price,
        seats: [],
      }
      section.seats.push({
        seatId: seat.seatId,
        row: seat.row,
        number: seat.number,
        available: seat.available,
      })
      sections.set(seat.section, section)
    }
    return ok({ scheduleId, serverTime: toKstIso(Date.now()), sections: [...sections.values()] })
  }),
]
