import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { login, logout } from '@/api/auth'
import { registerBillingKey, revokeBillingKey } from '@/api/billingKeys'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import {
  cancelEntry,
  enterLottery,
  entrySchema,
  getLottery,
  getMyEntry,
  getScheduleLottery,
  lotterySchema,
} from '@/api/lotteries'
import { sessionStore } from '@/auth/session'
import { mockDb } from '@/mocks/db'
import { setMockScenario } from '@/mocks/scenario'
import { signInAsDemo } from '@/test/api'

// 목이 서버의 추첨 API(LotteryController·LotteryEntryService, contracts-remaining 4절)와 같은지 확인한다.

const OPEN = 1
const BEFORE_START = 2
const DRAWN = 3
const DEMO_USER_ID = 42

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
    status?: number
    message?: string
    code?: string
    data?: Record<string, unknown>
    errors?: { field: string; reason: string }[]
  }
}

// 응답 틀(status·message·errors)까지 보려고 apiRequest를 거치지 않고 보낸다
async function raw(
  method: string,
  path: string,
  options: { key?: string | null; body?: unknown; signedIn?: boolean } = {},
): Promise<Raw> {
  const { key = null, body, signedIn = true } = options
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = sessionStore.getState().accessToken
  if (signedIn && token) {
    headers.Authorization = `Bearer ${token}`
  }
  if (key !== null) {
    headers['Idempotency-Key'] = key
  }
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: response.status, body: (await response.json()) as Raw['body'] }
}

function mockLottery(lotteryId: number) {
  return mockDb.lotteries.find((lottery) => lottery.lotteryId === lotteryId)!
}

describe('추첨 정보 목', () => {
  it('로그인 없이 받고, 서버의 응답 틀(200, "추첨 정보입니다", 필드, rules)과 스키마가 같다', async () => {
    const response = await raw('GET', `/lotteries/${OPEN}`, { signedIn: false })

    expect(response.status).toBe(200)
    expect(response.body.message).toBe('추첨 정보입니다')
    const info = lotterySchema.parse(response.body.data)
    const lottery = mockLottery(OPEN)
    expect(info).toMatchObject({
      lotteryId: OPEN,
      scheduleId: 32,
      status: 'OPEN',
      seatCount: 10,
      rules: {
        minPerEntry: 1,
        maxPerEntry: 2,
        adjacentSeatsGuaranteed: false,
        reentryAllowed: false,
        billingKeyRequired: true,
      },
    })
    // 시각은 Instant라 Z로 나간다(일반 예매의 +09:00과 다르다)
    for (const field of ['entryStartAt', 'entryEndAt', 'drawAt']) {
      expect(response.body.data?.[field]).toMatch(/Z$/)
    }
    expect(Date.parse(info.entryStartAt)).toBeLessThanOrEqual(lottery.entryStartAtMillis)
    expect(Object.keys(response.body.data ?? {}).sort()).toEqual(
      [
        'lotteryId',
        'scheduleId',
        'status',
        'seatCount',
        'entryStartAt',
        'entryEndAt',
        'drawAt',
        'rules',
      ].sort(),
    )
  })

  it('목 추첨 세 개: 응모 중, 응모 시작 전, 추첨 완료', async () => {
    const now = Date.now()
    const open = await getLottery(OPEN)
    const before = await getLottery(BEFORE_START)
    const drawn = await getLottery(DRAWN)

    expect(open).toMatchObject({ scheduleId: 32, status: 'OPEN', rules: { maxPerEntry: 2 } })
    expect(Date.parse(open.entryStartAt)).toBeLessThan(now)
    expect(Date.parse(open.entryEndAt)).toBeGreaterThan(now)
    expect(Date.parse(open.drawAt)).toBeGreaterThan(Date.parse(open.entryEndAt))
    expect(before).toMatchObject({ scheduleId: 41, status: 'OPEN', rules: { maxPerEntry: 1 } })
    expect(Date.parse(before.entryStartAt)).toBeGreaterThan(now)
    expect(drawn).toMatchObject({ scheduleId: 31, status: 'DRAWN' })
    expect(Date.parse(drawn.drawAt)).toBeLessThan(now)
  })

  it('없는 추첨은 404 NOT_FOUND이고, 숫자가 아닌 경로는 400 VALIDATION_FAILED다', async () => {
    const error = await rejectionOf(getLottery(999))
    expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])

    const bad = await raw('GET', '/lotteries/abc', { signedIn: false })
    expect([bad.status, bad.body.code]).toEqual([400, 'VALIDATION_FAILED'])
  })

  it('회차로 찾은 추첨은 추첨 정보와 같고, 추첨이 없는 회차는 null이다', async () => {
    await expect(getScheduleLottery(32)).resolves.toEqual(await getLottery(OPEN))
    await expect(getScheduleLottery(51)).resolves.toBeNull()

    const bad = await raw('GET', '/schedules/abc/lottery', { signedIn: false })
    expect([bad.status, bad.body.code]).toEqual([400, 'VALIDATION_FAILED'])
  })
})

describe('응모 목', () => {
  it('로그인하지 않으면 401이다', async () => {
    const error = await rejectionOf(enterLottery(OPEN, 1, newIdempotencyKey()))

    expect(error.status).toBe(401)
  })

  describe('로그인했을 때', () => {
    beforeEach(async () => {
      await signInAsDemo()
    })

    it('새 응모는 201 "응모했습니다"와 ENTERED이고 drawRank·reservationId·failReason은 null이다', async () => {
      const response = await raw('POST', `/lotteries/${OPEN}/entries`, {
        key: newIdempotencyKey(),
        body: { quantity: 2 },
      })

      expect(response.status).toBe(201)
      expect(response.body.message).toBe('응모했습니다')
      const entry = entrySchema.parse(response.body.data)
      expect(entry).toMatchObject({
        lotteryId: OPEN,
        status: 'ENTERED',
        quantity: 2,
        drawRank: null,
        reservationId: null,
        failReason: null,
      })
      await expect(getMyEntry(OPEN)).resolves.toEqual(entry)
    })

    it('Idempotency-Key가 없거나 UUID가 아니면 400 VALIDATION_FAILED다', async () => {
      for (const key of [null, 'not-a-uuid', '1-1-1-1-1']) {
        const response = await raw('POST', `/lotteries/${OPEN}/entries`, {
          key,
          body: { quantity: 1 },
        })
        expect([key, response.status, response.body.code]).toEqual([key, 400, 'VALIDATION_FAILED'])
      }
      expect(mockDb.lotteryEntries.filter((entry) => entry.lotteryId === OPEN)).toHaveLength(0)
    })

    it('quantity가 없거나 null이거나 1~2 밖이면 400이고 errors에 quantity가 있다', async () => {
      for (const body of [{}, { quantity: null }, { quantity: 0 }, { quantity: 3 }]) {
        const response = await raw('POST', `/lotteries/${OPEN}/entries`, {
          key: newIdempotencyKey(),
          body,
        })
        expect([body, response.status, response.body.code]).toEqual([
          body,
          400,
          'VALIDATION_FAILED',
        ])
        expect(response.body.errors?.map((error) => error.field)).toEqual(['quantity'])
      }
    })

    it('추첨의 maxPerEntry를 넘기면 400이고 errors는 없다', async () => {
      // 2번 추첨은 1매까지다. 응모 기간 안으로 옮겨 매수 검사만 남긴다
      const lottery = mockLottery(BEFORE_START)
      lottery.entryStartAtMillis = Date.now() - 1000

      const response = await raw('POST', `/lotteries/${BEFORE_START}/entries`, {
        key: newIdempotencyKey(),
        body: { quantity: 2 },
      })

      expect([response.status, response.body.code]).toEqual([400, 'VALIDATION_FAILED'])
      expect(response.body.errors).toBeUndefined()
    })

    it('같은 키·같은 내용이면 201로 그 응모의 지금 상태를 주고 새로 만들지 않는다', async () => {
      const key = newIdempotencyKey()
      const first = await enterLottery(OPEN, 2, key)

      const again = await enterLottery(OPEN, 2, key)

      expect(again).toEqual(first)
      expect(mockDb.lotteryEntries.filter((entry) => entry.lotteryId === OPEN)).toHaveLength(1)
      // 저장된 응답 재생이 아니라 지금 상태다
      await cancelEntry(OPEN)
      await expect(enterLottery(OPEN, 2, key)).resolves.toMatchObject({ status: 'CANCELED' })
    })

    it('멱등 확인이 기간 검사보다 먼저라 마감 뒤에도 같은 키는 201이다', async () => {
      const key = newIdempotencyKey()
      await enterLottery(OPEN, 1, key)
      const lottery = mockLottery(OPEN)
      lottery.entryEndAtMillis = Date.now() - 1000

      await expect(enterLottery(OPEN, 1, key)).resolves.toMatchObject({ status: 'ENTERED' })
    })

    it('같은 키에 다른 매수나 다른 추첨이면 409 IDEMPOTENCY_KEY_REUSED다', async () => {
      const key = newIdempotencyKey()
      await enterLottery(OPEN, 1, key)

      const otherQuantity = await rejectionOf(enterLottery(OPEN, 2, key))
      const otherLottery = await rejectionOf(enterLottery(DRAWN, 1, key))

      expect([otherQuantity.status, otherQuantity.code]).toEqual([409, 'IDEMPOTENCY_KEY_REUSED'])
      expect([otherLottery.status, otherLottery.code]).toEqual([409, 'IDEMPOTENCY_KEY_REUSED'])
    })

    it('키는 사용자별이 아니라 전역이라 다른 사용자가 같은 키를 쓰면 409다', async () => {
      const key = newIdempotencyKey()
      await enterLottery(OPEN, 1, key)
      await logout()
      mockDb.users.push({ ...mockDb.users[0], userId: 77, email: 'other@iseonjwa.dev' })
      await login({ email: 'other@iseonjwa.dev', password: mockDb.users[0].password })

      const error = await rejectionOf(enterLottery(OPEN, 1, key))

      expect([error.status, error.code]).toEqual([409, 'IDEMPOTENCY_KEY_REUSED'])
    })

    it('없는 추첨은 404 NOT_FOUND다', async () => {
      const error = await rejectionOf(enterLottery(999, 1, newIdempotencyKey()))

      expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])
    })

    it('이미 응모한 추첨에 다른 키로 응모하면 409 LOTTERY_ALREADY_ENTERED이고 취소한 뒤에도 같다', async () => {
      await enterLottery(OPEN, 1, newIdempotencyKey())

      const again = await rejectionOf(enterLottery(OPEN, 1, newIdempotencyKey()))
      await cancelEntry(OPEN)
      const afterCancel = await rejectionOf(enterLottery(OPEN, 1, newIdempotencyKey()))

      expect([again.status, again.code]).toEqual([409, 'LOTTERY_ALREADY_ENTERED'])
      expect([afterCancel.status, afterCancel.code]).toEqual([409, 'LOTTERY_ALREADY_ENTERED'])
    })

    it('이미 응모했으면 매수 초과보다 LOTTERY_ALREADY_ENTERED가 먼저다(서버의 거절 이유 순서)', async () => {
      mockLottery(BEFORE_START).entryStartAtMillis = Date.now() - 1000
      await enterLottery(BEFORE_START, 1, newIdempotencyKey())

      const error = await rejectionOf(enterLottery(BEFORE_START, 2, newIdempotencyKey()))

      expect(error.code).toBe('LOTTERY_ALREADY_ENTERED')
    })

    it('응모 시작 전·마감 뒤·상태가 OPEN이 아니면 409 LOTTERY_NOT_OPEN이다', async () => {
      const before = await rejectionOf(enterLottery(BEFORE_START, 1, newIdempotencyKey()))
      mockLottery(OPEN).entryEndAtMillis = Date.now() - 1000
      const afterEnd = await rejectionOf(enterLottery(OPEN, 1, newIdempotencyKey()))
      // 응모 기간 안이어도 상태가 CLOSED면 받지 않는다
      const closed = mockLottery(BEFORE_START)
      closed.entryStartAtMillis = Date.now() - 1000
      closed.status = 'CLOSED'
      const notOpen = await rejectionOf(enterLottery(BEFORE_START, 1, newIdempotencyKey()))

      for (const error of [before, afterEnd, notOpen]) {
        expect([error.status, error.code]).toEqual([409, 'LOTTERY_NOT_OPEN'])
      }
    })

    it('lottery-closed 시나리오에서는 응모가 409 LOTTERY_NOT_OPEN이고 응모 행이 생기지 않는다', async () => {
      setMockScenario({ lottery: 'closed' })

      const error = await rejectionOf(enterLottery(OPEN, 1, newIdempotencyKey()))

      expect([error.status, error.code]).toEqual([409, 'LOTTERY_NOT_OPEN'])
      await expect(getMyEntry(OPEN)).resolves.toBeNull()
    })

    it('lottery-unknown 시나리오에서는 첫 응모가 504이지만 서버에는 응모가 생기고, 같은 키로 다시 보내면 201이다', async () => {
      setMockScenario({ lottery: 'unknown' })
      const key = newIdempotencyKey()

      const error = await rejectionOf(enterLottery(OPEN, 2, key))

      expect(error.status).toBe(504)
      await expect(getMyEntry(OPEN)).resolves.toMatchObject({ status: 'ENTERED', quantity: 2 })
      await expect(enterLottery(OPEN, 2, key)).resolves.toMatchObject({ status: 'ENTERED' })
      // 504는 한 번뿐이다
      expect(mockDb.lotteryUnknownUsed).toBe(true)
    })
  })
})

describe('내 응모 목', () => {
  it('로그인하지 않으면 401을 던지고 null로 바꾸지 않는다', async () => {
    const error = await rejectionOf(getMyEntry(OPEN))

    expect(error.status).toBe(401)
  })

  describe('로그인했을 때', () => {
    beforeEach(async () => {
      await signInAsDemo()
    })

    it('응모하지 않았으면 404 NOT_FOUND이고 getMyEntry는 null이다', async () => {
      const response = await raw('GET', `/lotteries/${OPEN}/entries/me`)

      expect([response.status, response.body.code]).toEqual([404, 'NOT_FOUND'])
      await expect(getMyEntry(OPEN)).resolves.toBeNull()
    })

    it('응모했으면 200 "응모 결과입니다"로 준다. 취소한 응모도 그대로 준다', async () => {
      await enterLottery(OPEN, 1, newIdempotencyKey())
      await cancelEntry(OPEN)

      const response = await raw('GET', `/lotteries/${OPEN}/entries/me`)

      expect(response.status).toBe(200)
      expect(response.body.message).toBe('응모 결과입니다')
      expect(entrySchema.parse(response.body.data).status).toBe('CANCELED')
    })

    it('3번 추첨은 카드가 있으면 처음 조회 WON(순위 1, 예매 번호 있음), 다음 조회부터 PAID이고 그 예매는 확정이다', async () => {
      await registerBillingKey('demo-1')
      const first = await getMyEntry(DRAWN)
      const second = await getMyEntry(DRAWN)
      const third = await getMyEntry(DRAWN)

      expect(first).toMatchObject({ status: 'WON', drawRank: 1, quantity: 1, failReason: null })
      expect(first?.reservationId).not.toBeNull()
      expect(second).toMatchObject({ status: 'PAID', reservationId: first?.reservationId })
      expect(third).toEqual(second)
      const reservation = mockDb.reservations.get(second!.reservationId!)!
      expect(reservation).toMatchObject({ status: 'CONFIRMED', userId: DEMO_USER_ID })
      // 당첨 예매가 잡은 좌석은 팔린 상태다
      for (const seatId of reservation.seatIds) {
        expect(mockDb.seats.find((seat) => seat.seatId === seatId)?.available).toBe(false)
      }
    })

    it('3번 추첨의 WON 동안 예매는 결제 대기(HELD)이다', async () => {
      const won = await getMyEntry(DRAWN)

      expect(mockDb.reservations.get(won!.reservationId!)?.status).toBe('HELD')
    })

    it.each([
      ['waitlisted', { status: 'WAITLISTED', drawRank: 13, reservationId: null }],
      ['lost', { status: 'LOST', drawRank: null, reservationId: null }],
    ] as const)('lottery-%s 시나리오에서는 처음 조회부터 %o', async (scenario, expected) => {
      setMockScenario({ lottery: scenario })

      await expect(getMyEntry(DRAWN)).resolves.toMatchObject(expected)
      await expect(getMyEntry(DRAWN)).resolves.toMatchObject(expected)
    })

    it('카드가 없으면 처음 조회 WON, 다음 조회부터 FAILED(BILLING_KEY_MISSING)이고 예매는 풀린다(자동결제 거절)', async () => {
      const first = await getMyEntry(DRAWN)
      const second = await getMyEntry(DRAWN)

      expect(first?.status).toBe('WON')
      expect(second).toMatchObject({ status: 'FAILED', failReason: 'BILLING_KEY_MISSING' })
      expect(mockDb.reservations.get(first!.reservationId!)?.status).toBe('RELEASED')
      await expect(getMyEntry(DRAWN)).resolves.toEqual(second)
    })

    it('해지한 카드는 없는 것으로 본다(ACTIVE 빌링키만 센다)', async () => {
      await registerBillingKey('demo-1')
      await revokeBillingKey()

      await getMyEntry(DRAWN)

      await expect(getMyEntry(DRAWN)).resolves.toMatchObject({
        status: 'FAILED',
        failReason: 'BILLING_KEY_MISSING',
      })
    })

    it('카드를 당첨 뒤 결제 전에 등록해도 결제 시점에 있으면 PAID이다', async () => {
      await getMyEntry(DRAWN)
      await registerBillingKey('demo-1')

      await expect(getMyEntry(DRAWN)).resolves.toMatchObject({ status: 'PAID' })
    })

    it('lottery-failed 시나리오에서는 카드가 있어도 FAILED(BILLING_KEY_MISSING)이다', async () => {
      await registerBillingKey('demo-1')
      setMockScenario({ lottery: 'failed' })

      const first = await getMyEntry(DRAWN)
      const second = await getMyEntry(DRAWN)

      expect(first?.status).toBe('WON')
      expect(second).toMatchObject({ status: 'FAILED', failReason: 'BILLING_KEY_MISSING' })
      expect(mockDb.reservations.get(first!.reservationId!)?.status).toBe('RELEASED')
    })
  })
})

describe('응모 취소 목', () => {
  it('로그인하지 않으면 401이다', async () => {
    const error = await rejectionOf(cancelEntry(OPEN))

    expect(error.status).toBe(401)
  })

  describe('로그인했을 때', () => {
    beforeEach(async () => {
      await signInAsDemo()
    })

    it('응모가 없으면 404 NOT_FOUND다', async () => {
      const error = await rejectionOf(cancelEntry(OPEN))

      expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])
    })

    it('마감 전 ENTERED는 200 "응모를 취소했습니다"와 CANCELED이고, 다시 취소해도 200으로 같다', async () => {
      await enterLottery(OPEN, 1, newIdempotencyKey())

      const response = await raw('DELETE', `/lotteries/${OPEN}/entries/me`)
      const again = await raw('DELETE', `/lotteries/${OPEN}/entries/me`)

      expect(response.status).toBe(200)
      expect(response.body.message).toBe('응모를 취소했습니다')
      expect(entrySchema.parse(response.body.data).status).toBe('CANCELED')
      expect(again.status).toBe(200)
      expect(again.body.data).toEqual(response.body.data)
    })

    it('마감이 지났으면 409 LOTTERY_NOT_OPEN이고 응모는 ENTERED로 남는다', async () => {
      await enterLottery(OPEN, 1, newIdempotencyKey())
      mockLottery(OPEN).entryEndAtMillis = Date.now() - 1000

      const error = await rejectionOf(cancelEntry(OPEN))

      expect([error.status, error.code]).toEqual([409, 'LOTTERY_NOT_OPEN'])
      await expect(getMyEntry(OPEN)).resolves.toMatchObject({ status: 'ENTERED' })
    })

    it('추첨 결과가 난 응모(WON·LOST 등)는 409 LOTTERY_NOT_OPEN이다', async () => {
      const error = await rejectionOf(cancelEntry(DRAWN))

      expect([error.status, error.code]).toEqual([409, 'LOTTERY_NOT_OPEN'])
    })
  })
})

describe('추첨 상태는 시각에 따라 바뀐다(서버 마감·추첨 스케줄러)', () => {
  const row = (lotteryId: number) =>
    mockDb.lotteries.find((lottery) => lottery.lotteryId === lotteryId)!

  it('응모 마감 전에는 OPEN이다', async () => {
    await expect(getLottery(OPEN)).resolves.toMatchObject({ status: 'OPEN' })
  })

  it('마감 시각이 지나고 추첨 시각 전이면 CLOSED가 된다', async () => {
    row(OPEN).entryEndAtMillis = Date.now() - 1000

    await expect(getLottery(OPEN)).resolves.toMatchObject({ status: 'CLOSED' })
    expect(row(OPEN).status).toBe('CLOSED')
  })

  it('추첨 시각이 지나면 DRAWN이 된다(마감 처리를 건너뛰어도)', async () => {
    row(OPEN).entryEndAtMillis = Date.now() - 2000
    row(OPEN).drawAtMillis = Date.now() - 1000

    await expect(getLottery(OPEN)).resolves.toMatchObject({ status: 'DRAWN' })
  })

  it('이미 DRAWN·DONE이면 그대로다', async () => {
    row(DRAWN).status = 'DONE'

    await expect(getLottery(DRAWN)).resolves.toMatchObject({ status: 'DONE' })
  })

  it('응모 시작 전(2번)은 OPEN으로 남는다', async () => {
    await expect(getLottery(2)).resolves.toMatchObject({ status: 'OPEN' })
  })

  it('요청마다 반영한다: 응모는 마감 뒤 LOTTERY_NOT_OPEN이고 ENTERED 응모는 추첨되지 않고 ENTERED로 남는다', async () => {
    await signInAsDemo()
    await enterLottery(OPEN, 1, newIdempotencyKey())
    row(OPEN).entryEndAtMillis = Date.now() - 2000
    row(OPEN).drawAtMillis = Date.now() - 1000

    await expect(getMyEntry(OPEN)).resolves.toMatchObject({ status: 'ENTERED', drawRank: null })
    expect(row(OPEN).status).toBe('DRAWN')
    const cancel = await rejectionOf(cancelEntry(OPEN))
    expect(cancel.code).toBe('LOTTERY_NOT_OPEN')
  })
})
