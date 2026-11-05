import { beforeEach, describe, expect, it } from 'vitest'
import { createLottery, updateLottery, type LotteryCreateInput } from '@/api/adminLotteries'
import { ApiError } from '@/api/apiError'
import { getLottery } from '@/api/lotteries'
import { mockDb } from '@/mocks/db'
import { sessionStore } from '@/auth/session'
import { signInAsAdmin, signInAsDemo } from '@/test/api'
import {
  DAY,
  HOUR,
  iso,
  kstTimeAfterDays,
  lotteryInput,
  MINUTE,
  seatsOf,
  seedScheduleForLottery,
} from '@/test/adminLotteries'

// 목이 서버의 운영자 추첨 API(AdminLotteryController·LotteryAdminService·Lottery, contracts-remaining 5절)와 같은지 확인한다.

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

interface Raw {
  status: number
  body: {
    message?: string
    code?: string
    data?: Record<string, unknown>
    errors?: { field: string; reason: string }[]
  }
}

async function raw(method: string, path: string, body?: unknown): Promise<Raw> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = sessionStore.getState().accessToken
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: response.status, body: (await response.json()) as Raw['body'] }
}

const lotteryOf = (lotteryId: number) =>
  mockDb.lotteries.find((lottery) => lottery.lotteryId === lotteryId)!
const unavailableOf = (scheduleId: number) =>
  seatsOf(scheduleId).filter((seat) => !seat.available).length

describe('추첨 설정 목: 권한', () => {
  it('로그인하지 않으면 401이다', async () => {
    const scheduleId = seedScheduleForLottery()

    const error = await rejectionOf(createLottery(scheduleId, lotteryInput()))

    expect(error.status).toBe(401)
  })

  it('일반 사용자는 403 FORBIDDEN이고 수정도 같다', async () => {
    const scheduleId = seedScheduleForLottery()
    await signInAsDemo()

    const created = await rejectionOf(createLottery(scheduleId, lotteryInput()))
    const updated = await rejectionOf(updateLottery(2, { seatCount: 3 }))

    expect([created.status, created.code]).toEqual([403, 'FORBIDDEN'])
    expect([updated.status, updated.code]).toEqual([403, 'FORBIDDEN'])
    expect(mockDb.lotteries).toHaveLength(3)
  })
})

describe('추첨 설정 목', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  it('성공하면 201 "추첨을 설정했습니다"와 서버 응답 모양(OPEN, 시각은 Z)을 준다', async () => {
    const scheduleId = seedScheduleForLottery()
    const input = lotteryInput({ seatCount: 5, waitlistCount: 2, maxPerEntry: 1 })

    const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, input)

    expect(response.status).toBe(201)
    expect(response.body.message).toBe('추첨을 설정했습니다')
    expect(Object.keys(response.body.data ?? {}).sort()).toEqual(
      [
        'lotteryId',
        'scheduleId',
        'status',
        'sourceGrade',
        'seatCount',
        'waitlistCount',
        'maxPerEntry',
        'entryStartAt',
        'entryEndAt',
        'drawAt',
        'warnings',
      ].sort(),
    )
    expect(response.body.data).toMatchObject({
      scheduleId,
      status: 'OPEN',
      sourceGrade: 'VIP',
      seatCount: 5,
      waitlistCount: 2,
      maxPerEntry: 1,
      warnings: [],
    })
    for (const field of ['entryStartAt', 'entryEndAt', 'drawAt']) {
      expect(response.body.data?.[field]).toMatch(/Z$/)
    }
  })

  it('만든 추첨은 공개 조회로 보이고, 추첨석 수만큼 그 등급의 앞 좌석이 팔 수 없는 상태가 된다', async () => {
    const scheduleId = seedScheduleForLottery()
    const input = lotteryInput({ seatCount: 3, maxPerEntry: 1 })

    const created = await createLottery(scheduleId, input)

    const info = await getLottery(created.lotteryId)
    expect(info).toMatchObject({ scheduleId, status: 'OPEN', seatCount: 3 })
    expect(info.rules.maxPerEntry).toBe(1)
    expect(Date.parse(info.drawAt)).toBe(Date.parse(input.drawAt))
    const seats = seatsOf(scheduleId).sort((left, right) => left.seatId - right.seatId)
    expect(seats.slice(0, 3).every((seat) => !seat.available)).toBe(true)
    expect(unavailableOf(scheduleId)).toBe(3)
  })

  it('추첨 시각이 한국 시간 03:00 이상 05:00 미만이 아니면 경고를 준다(경계 포함)', async () => {
    const cases: [number, number, boolean][] = [
      [2, 59, true],
      [3, 0, false],
      [4, 59, false],
      [5, 0, true],
    ]
    for (const [hour, minute, warned] of cases) {
      const scheduleId = seedScheduleForLottery()
      const created = await createLottery(
        scheduleId,
        lotteryInput({ drawAt: iso(kstTimeAfterDays(4, hour, minute)) }),
      )
      expect([hour, minute, created.warnings]).toEqual([
        hour,
        minute,
        warned ? ['DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW'] : [],
      ])
    }
  })

  it('+09:00 오프셋 시각도 받는다', async () => {
    const scheduleId = seedScheduleForLottery()
    const now = Date.now()
    const input = lotteryInput({
      entryStartAt: new Date(now + DAY + 9 * HOUR).toISOString().slice(0, 19) + '+09:00',
    })
    // 위 값은 UTC로 읽으면 9시간 뒤가 아니라 같은 순간이다. 형식만 확인한다
    await expect(createLottery(scheduleId, input)).resolves.toMatchObject({ scheduleId })
  })

  describe('필수 칸과 형식(400 + errors)', () => {
    it('빈 본문이면 일곱 칸 모두 오류로 알린다', async () => {
      const scheduleId = seedScheduleForLottery()

      const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {})

      expect([response.status, response.body.code]).toEqual([400, 'VALIDATION_FAILED'])
      expect(response.body.errors?.map((error) => error.field).sort()).toEqual(
        [
          'sourceGrade',
          'seatCount',
          'waitlistCount',
          'maxPerEntry',
          'entryStartAt',
          'entryEndAt',
          'drawAt',
        ].sort(),
      )
    })

    it.each([
      ['seatCount', 0],
      ['waitlistCount', -1],
      ['maxPerEntry', 0],
      ['maxPerEntry', 3],
      ['seatCount', null],
    ] as const)('%s가 %s이면 그 칸 오류다', async (field, value) => {
      const scheduleId = seedScheduleForLottery()

      const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {
        ...lotteryInput(),
        [field]: value,
      })

      expect(response.status).toBe(400)
      expect(response.body.errors?.map((error) => error.field)).toEqual([field])
    })

    it.each([
      ['모르는 등급', { sourceGrade: 'GOLD' }],
      ['LOTTERY 등급', { sourceGrade: 'LOTTERY' }],
      ['숫자가 아닌 추첨석 수', { seatCount: 'many' }],
      ['시각이 아닌 값', { drawAt: 'tomorrow' }],
      ['오프셋 없는 시각', { entryStartAt: '2099-01-01T10:00:00' }],
    ])('%s는 칸별 오류 없는 400이다', async (_name, patch) => {
      const scheduleId = seedScheduleForLottery()

      const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {
        ...lotteryInput(),
        ...patch,
      })

      expect([response.status, response.body.code]).toEqual([400, 'VALIDATION_FAILED'])
      expect(response.body.errors).toBeUndefined()
    })
  })

  describe('값 사이 규칙(400, errors 없음)', () => {
    async function expectRule(patch: Partial<LotteryCreateInput>) {
      const scheduleId = seedScheduleForLottery()
      const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {
        ...lotteryInput(),
        ...patch,
      })
      expect([response.status, response.body.code]).toEqual([400, 'VALIDATION_FAILED'])
      expect(response.body.errors).toBeUndefined()
      expect(mockDb.lotteries).toHaveLength(3)
      expect(unavailableOf(scheduleId)).toBe(0)
    }

    it('응모 시작이 마감과 같거나 뒤면 안 된다', async () => {
      const now = Date.now()
      await expectRule({ entryStartAt: iso(now + 2 * DAY), entryEndAt: iso(now + 2 * DAY) })
      await expectRule({ entryStartAt: iso(now + 3 * DAY), entryEndAt: iso(now + 2 * DAY) })
    })

    it('마감이 추첨보다 뒤면 안 된다(같으면 된다)', async () => {
      const now = Date.now()
      await expectRule({ entryEndAt: iso(now + 3 * DAY), drawAt: iso(now + 3 * DAY - MINUTE) })

      const scheduleId = seedScheduleForLottery()
      const end = now + 3 * DAY
      await expect(
        createLottery(scheduleId, lotteryInput({ entryEndAt: iso(end), drawAt: iso(end) })),
      ).resolves.toMatchObject({ scheduleId })
    })

    it('추첨 + 60분이 공연 시작보다 뒤면 안 된다(같으면 된다)', async () => {
      const startsAfter = 10 * DAY
      const scheduleId = seedScheduleForLottery({ startsAfter })
      const startsAt = mockDb.schedules.find(
        (schedule) => schedule.scheduleId === scheduleId,
      )!.startsAtMillis

      const late = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {
        ...lotteryInput(),
        drawAt: iso(startsAt - HOUR + MINUTE),
      })
      expect([late.status, late.body.errors]).toEqual([400, undefined])

      await expect(
        createLottery(scheduleId, lotteryInput({ drawAt: iso(startsAt - HOUR) })),
      ).resolves.toMatchObject({ scheduleId })
    })
  })

  describe('상태 오류(404·409)와 검사 순서', () => {
    it('없는 회차는 404 NOT_FOUND다', async () => {
      const error = await rejectionOf(createLottery(999_999, lotteryInput()))

      expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])
    })

    it('본문 검증이 회차 확인보다 먼저라 잘못된 본문과 없는 회차는 400이다', async () => {
      const response = await raw('POST', '/admin/schedules/999999/lotteries', {})

      expect(response.status).toBe(400)
    })

    it('회차에 추첨이 이미 있으면 409 LOTTERY_ALREADY_EXISTS이고, 값 사이 규칙보다 먼저 본다', async () => {
      const scheduleId = seedScheduleForLottery()
      await createLottery(scheduleId, lotteryInput())

      const again = await rejectionOf(createLottery(scheduleId, lotteryInput()))
      const brokenRule = await rejectionOf(
        createLottery(scheduleId, lotteryInput({ entryEndAt: iso(Date.now()) })),
      )

      expect([again.status, again.code]).toEqual([409, 'LOTTERY_ALREADY_EXISTS'])
      expect(brokenRule.code).toBe('LOTTERY_ALREADY_EXISTS')
    })

    it('시드 추첨이 있는 회차(32)에도 409다', async () => {
      const error = await rejectionOf(createLottery(32, lotteryInput()))

      expect(error.code).toBe('LOTTERY_ALREADY_EXISTS')
    })

    it('예매 오픈이 지났으면 409 LOTTERY_SETUP_CLOSED다', async () => {
      const scheduleId = seedScheduleForLottery({ openAfter: -MINUTE })

      const error = await rejectionOf(createLottery(scheduleId, lotteryInput()))

      expect([error.status, error.code]).toEqual([409, 'LOTTERY_SETUP_CLOSED'])
      expect(unavailableOf(scheduleId)).toBe(0)
    })

    it('응모 시작이 지금보다 앞이면 409 LOTTERY_SETUP_CLOSED다', async () => {
      const scheduleId = seedScheduleForLottery()
      const now = Date.now()

      const error = await rejectionOf(
        createLottery(scheduleId, lotteryInput({ entryStartAt: iso(now - MINUTE) })),
      )

      expect(error.code).toBe('LOTTERY_SETUP_CLOSED')
    })

    it('값 사이 규칙 위반이 설정 마감보다 먼저다(400)', async () => {
      const scheduleId = seedScheduleForLottery({ openAfter: -MINUTE })

      const response = await raw('POST', `/admin/schedules/${scheduleId}/lotteries`, {
        ...lotteryInput(),
        entryEndAt: iso(Date.now()),
      })

      expect(response.status).toBe(400)
    })

    it('설정 마감이 좌석 부족보다 먼저다(409 LOTTERY_SETUP_CLOSED)', async () => {
      const scheduleId = seedScheduleForLottery({ openAfter: -MINUTE })

      const error = await rejectionOf(createLottery(scheduleId, lotteryInput({ seatCount: 500 })))

      expect(error.code).toBe('LOTTERY_SETUP_CLOSED')
    })

    it('뗄 등급 좌석이 모자라면 409 LOTTERY_SEATS_NOT_ENOUGH이고 추첨도 좌석도 바뀌지 않는다', async () => {
      const scheduleId = seedScheduleForLottery()

      const tooMany = await rejectionOf(createLottery(scheduleId, lotteryInput({ seatCount: 21 })))
      const otherGrade = await rejectionOf(
        createLottery(scheduleId, lotteryInput({ sourceGrade: 'R' })),
      )

      expect([tooMany.status, tooMany.code]).toEqual([409, 'LOTTERY_SEATS_NOT_ENOUGH'])
      expect(otherGrade.code).toBe('LOTTERY_SEATS_NOT_ENOUGH')
      expect(unavailableOf(scheduleId)).toBe(0)
      expect(mockDb.lotteries.some((lottery) => lottery.scheduleId === scheduleId)).toBe(false)
    })

    it('좌석을 만들지 않은 회차도 409 LOTTERY_SEATS_NOT_ENOUGH다', async () => {
      const scheduleId = seedScheduleForLottery({ seats: false })

      const error = await rejectionOf(createLottery(scheduleId, lotteryInput()))

      expect(error.code).toBe('LOTTERY_SEATS_NOT_ENOUGH')
    })

    it('이미 팔린 좌석은 떼지 않는다(빈 좌석만 센다)', async () => {
      const scheduleId = seedScheduleForLottery()
      seatsOf(scheduleId)
        .slice(0, 18)
        .forEach((seat) => (seat.available = false))

      const error = await rejectionOf(createLottery(scheduleId, lotteryInput({ seatCount: 3 })))

      expect(error.code).toBe('LOTTERY_SEATS_NOT_ENOUGH')
      await expect(
        createLottery(scheduleId, lotteryInput({ seatCount: 2 })),
      ).resolves.toMatchObject({ seatCount: 2 })
    })
  })
})

describe('추첨 수정 목', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  async function createdLottery(options: { seatCount?: number } = {}) {
    const scheduleId = seedScheduleForLottery()
    const lottery = await createLottery(
      scheduleId,
      lotteryInput({ seatCount: options.seatCount ?? 5 }),
    )
    return { scheduleId, lottery }
  }

  it('보낸 필드만 고치고 200 "추첨 설정을 고쳤습니다"와 같은 응답 모양을 준다', async () => {
    const { lottery } = await createdLottery()

    const response = await raw('PATCH', `/admin/lotteries/${lottery.lotteryId}`, {
      waitlistCount: 9,
    })

    expect(response.status).toBe(200)
    expect(response.body.message).toBe('추첨 설정을 고쳤습니다')
    expect(response.body.data).toEqual({ ...lottery, waitlistCount: 9 })
    await expect(getLottery(lottery.lotteryId)).resolves.toMatchObject({ seatCount: 5 })
  })

  it('빈 본문이면 아무것도 바꾸지 않고 200이다', async () => {
    const { lottery } = await createdLottery()

    await expect(updateLottery(lottery.lotteryId, {})).resolves.toEqual(lottery)
  })

  it('시각과 매수를 고치면 공개 조회에도 반영된다', async () => {
    const { lottery } = await createdLottery()
    const newDraw = iso(kstTimeAfterDays(5, 3, 30))

    const updated = await updateLottery(lottery.lotteryId, { drawAt: newDraw, maxPerEntry: 1 })

    expect(Date.parse(updated.drawAt)).toBe(Date.parse(newDraw))
    const info = await getLottery(lottery.lotteryId)
    expect(info.rules.maxPerEntry).toBe(1)
    expect(Date.parse(info.drawAt)).toBe(Date.parse(newDraw))
  })

  it('경고는 합친 값의 추첨 시각으로 다시 정한다', async () => {
    const { lottery } = await createdLottery()

    const outside = await updateLottery(lottery.lotteryId, {
      drawAt: iso(kstTimeAfterDays(5, 12)),
    })
    const inside = await updateLottery(lottery.lotteryId, { drawAt: iso(kstTimeAfterDays(5, 3)) })

    expect(outside.warnings).toEqual(['DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW'])
    expect(inside.warnings).toEqual([])
  })

  it('추첨석 수를 늘리면 차이만큼 더 떼고 줄이면 풀어 준다', async () => {
    const { scheduleId, lottery } = await createdLottery({ seatCount: 5 })
    expect(unavailableOf(scheduleId)).toBe(5)

    await updateLottery(lottery.lotteryId, { seatCount: 8 })
    expect(unavailableOf(scheduleId)).toBe(8)

    await updateLottery(lottery.lotteryId, { seatCount: 2 })
    expect(unavailableOf(scheduleId)).toBe(2)
  })

  it('늘릴 좌석이 모자라면 409 LOTTERY_SEATS_NOT_ENOUGH이고 아무것도 바뀌지 않는다', async () => {
    const { scheduleId, lottery } = await createdLottery({ seatCount: 5 })

    const error = await rejectionOf(updateLottery(lottery.lotteryId, { seatCount: 25 }))

    expect([error.status, error.code]).toEqual([409, 'LOTTERY_SEATS_NOT_ENOUGH'])
    expect(unavailableOf(scheduleId)).toBe(5)
    expect(lotteryOf(lottery.lotteryId).seatCount).toBe(5)
  })

  it('등급을 바꾸면 앞 등급 좌석을 풀고 새 등급에서 다시 뗀다', async () => {
    const { scheduleId, lottery } = await createdLottery({ seatCount: 5 })
    const base = seatsOf(scheduleId)[0]
    const rSeats = Array.from({ length: 4 }, (_, index) => ({
      ...base,
      seatId: 700_000 + index,
      grade: 'R',
      section: 'B',
      available: true,
    }))
    mockDb.seats.push(...rSeats)

    const tooFew = await rejectionOf(updateLottery(lottery.lotteryId, { sourceGrade: 'R' }))
    expect(tooFew.code).toBe('LOTTERY_SEATS_NOT_ENOUGH')
    expect(unavailableOf(scheduleId)).toBe(5)

    const moved = await updateLottery(lottery.lotteryId, { sourceGrade: 'R', seatCount: 4 })

    expect(moved.sourceGrade).toBe('R')
    expect(
      seatsOf(scheduleId).filter((seat) => seat.grade === 'VIP' && !seat.available),
    ).toHaveLength(0)
    expect(
      seatsOf(scheduleId).filter((seat) => seat.grade === 'R' && !seat.available),
    ).toHaveLength(4)
  })

  it('없는 추첨은 404 NOT_FOUND다', async () => {
    const error = await rejectionOf(updateLottery(999_999, { seatCount: 3 }))

    expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])
  })

  it('보낸 칸의 범위를 어기면 400 + errors다', async () => {
    const { lottery } = await createdLottery()

    const response = await raw('PATCH', `/admin/lotteries/${lottery.lotteryId}`, {
      seatCount: 0,
      maxPerEntry: 3,
    })

    expect(response.status).toBe(400)
    expect(response.body.errors?.map((error) => error.field).sort()).toEqual([
      'maxPerEntry',
      'seatCount',
    ])
  })

  it('합친 값이 값 사이 규칙을 어기면 400(errors 없음)이고 바뀌지 않는다', async () => {
    const { lottery } = await createdLottery()

    const response = await raw('PATCH', `/admin/lotteries/${lottery.lotteryId}`, {
      entryEndAt: iso(Date.now() + HOUR),
    })

    expect([response.status, response.body.errors]).toEqual([400, undefined])
    expect(lotteryOf(lottery.lotteryId).entryEndAtMillis).toBe(Date.parse(lottery.entryEndAt))
  })

  it('응모가 이미 시작됐으면(지금 값 기준) 409 LOTTERY_SETUP_CLOSED이고, 본문이 잘못돼도 이 오류가 먼저다', async () => {
    const { lottery } = await createdLottery()
    lotteryOf(lottery.lotteryId).entryStartAtMillis = Date.now() - MINUTE

    const closed = await rejectionOf(updateLottery(lottery.lotteryId, { seatCount: 3 }))
    const brokenToo = await raw('PATCH', `/admin/lotteries/${lottery.lotteryId}`, {
      entryEndAt: iso(Date.now() - HOUR),
    })

    expect([closed.status, closed.code]).toEqual([409, 'LOTTERY_SETUP_CLOSED'])
    expect([brokenToo.status, brokenToo.body.code]).toEqual([409, 'LOTTERY_SETUP_CLOSED'])
  })

  it('예매 오픈이 지난 회차도 409 LOTTERY_SETUP_CLOSED다', async () => {
    const { scheduleId, lottery } = await createdLottery()
    mockDb.schedules.find((schedule) => schedule.scheduleId === scheduleId)!.openAtMillis =
      Date.now() - MINUTE

    const error = await rejectionOf(updateLottery(lottery.lotteryId, { seatCount: 3 }))

    expect(error.code).toBe('LOTTERY_SETUP_CLOSED')
  })

  it('새 응모 시작이 지금보다 앞이면 409 LOTTERY_SETUP_CLOSED다(값 사이 규칙 다음에 본다)', async () => {
    const { lottery } = await createdLottery()

    const closed = await rejectionOf(
      updateLottery(lottery.lotteryId, { entryStartAt: iso(Date.now() - MINUTE) }),
    )
    const broken = await raw('PATCH', `/admin/lotteries/${lottery.lotteryId}`, {
      entryStartAt: iso(Date.now() - MINUTE),
      entryEndAt: iso(Date.now() - HOUR),
    })

    expect(closed.code).toBe('LOTTERY_SETUP_CLOSED')
    expect(broken.status).toBe(400)
  })

  it('시드 추첨: 1번은 응모가 시작돼 409이고, 2번(응모 시작 전)은 좌석이 없어 늘릴 수 없지만 줄일 수 있다', async () => {
    const closed = await rejectionOf(updateLottery(1, { seatCount: 3 }))
    const increase = await rejectionOf(updateLottery(2, { seatCount: 12 }))

    expect(closed.code).toBe('LOTTERY_SETUP_CLOSED')
    // 회차 41에는 좌석이 없어 늘리는 쪽은 좌석 부족이다
    expect(increase.code).toBe('LOTTERY_SEATS_NOT_ENOUGH')
    await expect(updateLottery(2, { seatCount: 8 })).resolves.toMatchObject({ seatCount: 8 })
  })
})
