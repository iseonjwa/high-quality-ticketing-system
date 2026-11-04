import { describe, expect, it } from 'vitest'
import { createConcert, createSchedule, createScheduleSeats } from '@/api/admin'
import { getConcert, getConcerts, getScheduleSeats } from '@/api/concerts'
import { logout } from '@/api/auth'
import { admitTo, recordRequests, signInAsAdmin, signInAsDemo } from '@/test/api'
import { mockDb } from '@/mocks/db'

// 판매 중 회차가 되도록 오픈은 과거, 시작은 먼 미래로 둔다
const STARTS_AT = '2099-12-20T19:00:00+09:00'
const OPEN_AT = '2020-12-10T20:00:00+09:00'
const PRICES = [
  { grade: 'VIP', price: 165000 },
  { grade: 'R', price: 143000 },
  { grade: 'S', price: 112000 },
] as const

async function createConcertWithSchedule() {
  const { concertId } = await createConcert({ title: '새 공연', description: '설명' })
  const { scheduleId } = await createSchedule(concertId, {
    venueId: 1,
    startsAt: STARTS_AT,
    openAt: OPEN_AT,
  })
  return { concertId, scheduleId }
}

describe('운영자 등록 API', () => {
  it('공연 → 회차 → 좌석을 만들면 공연 목록·상세와 좌석 조회에 보인다', async () => {
    await signInAsAdmin()
    const { concertId, scheduleId } = await createConcertWithSchedule()
    const seats = await createScheduleSeats(scheduleId, [...PRICES])

    expect(seats).toEqual({ scheduleId, createdSeats: 60 })
    const list = await getConcerts()
    expect(list.content.map((concert) => concert.concertId)).toContain(concertId)
    const detail = await getConcert(concertId)
    expect(detail.title).toBe('새 공연')
    expect(detail.schedules).toHaveLength(1)
    expect(detail.schedules[0]).toMatchObject({ scheduleId, venueName: 'KSPO DOME', maxPerUser: 4 })

    await logout()
    await signInAsDemo()
    await admitTo(scheduleId)
    const view = await getScheduleSeats(scheduleId)
    expect(view.sections.map((section) => [section.grade, section.price])).toEqual([
      ['VIP', 165000],
      ['R', 143000],
      ['S', 112000],
    ])
    expect(view.sections.flatMap((section) => section.seats)).toHaveLength(60)
  })

  it('비운 선택 값은 본문에서 빠진 채로 보낸다', async () => {
    await signInAsAdmin()
    const requests = recordRequests('/api/v1/admin/concerts')
    await createConcert({ title: '제목만' })
    expect(await requests[0].text()).toBe(JSON.stringify({ title: '제목만' }))
  })

  it('USER는 세 API 모두 403이다', async () => {
    await signInAsDemo()
    const forbidden = { status: 403, code: 'FORBIDDEN' }
    await expect(createConcert({ title: 'x' })).rejects.toMatchObject(forbidden)
    await expect(
      createSchedule(7, { venueId: 1, startsAt: STARTS_AT, openAt: OPEN_AT }),
    ).rejects.toMatchObject(forbidden)
    await expect(createScheduleSeats(32, [...PRICES])).rejects.toMatchObject(forbidden)
    expect(mockDb.concerts).toHaveLength(3)
  })

  it('회차: 오픈이 시작보다 늦으면 400이다', async () => {
    await signInAsAdmin()
    await expect(
      createSchedule(7, { venueId: 1, startsAt: OPEN_AT, openAt: STARTS_AT }),
    ).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' })
  })

  it('회차: 없는 공연이나 공연장은 404이다', async () => {
    await signInAsAdmin()
    const input = { venueId: 1, startsAt: STARTS_AT, openAt: OPEN_AT }
    await expect(createSchedule(9999, input)).rejects.toMatchObject({ status: 404 })
    await expect(createSchedule(7, { ...input, venueId: 99 })).rejects.toMatchObject({
      status: 404,
    })
  })

  it('좌석: 같은 회차에 두 번째 생성은 409이다', async () => {
    await signInAsAdmin()
    const { scheduleId } = await createConcertWithSchedule()
    await createScheduleSeats(scheduleId, [...PRICES])
    await expect(createScheduleSeats(scheduleId, [...PRICES])).rejects.toMatchObject({
      status: 409,
      code: 'SCHEDULE_SEATS_ALREADY_CREATED',
    })
  })

  it('좌석: 가격 등급이 공연장과 다르면 400이고 좌석은 만들어지지 않는다', async () => {
    await signInAsAdmin()
    const { scheduleId } = await createConcertWithSchedule()
    await expect(
      createScheduleSeats(scheduleId, [
        { grade: 'VIP', price: 165000 },
        { grade: 'R', price: 143000 },
      ]),
    ).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' })
    await expect(
      createScheduleSeats(scheduleId, [...PRICES, { grade: 'LOTTERY', price: 1000 }]),
    ).rejects.toMatchObject({ status: 400 })
    expect(mockDb.seats.some((seat) => seat.scheduleId === scheduleId)).toBe(false)
  })

  it('좌석: 같은 등급이 두 번이거나 없는 회차면 실패한다', async () => {
    await signInAsAdmin()
    const { scheduleId } = await createConcertWithSchedule()
    await expect(
      createScheduleSeats(scheduleId, [...PRICES, { grade: 'S', price: 1 }]),
    ).rejects.toMatchObject({ status: 400 })
    await expect(createScheduleSeats(9999, [...PRICES])).rejects.toMatchObject({ status: 404 })
  })
})
