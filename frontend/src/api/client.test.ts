import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { z } from 'zod'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { getMe, logout } from '@/api/auth'
import { apiRequest } from '@/api/client'
import { getScheduleSeats } from '@/api/concerts'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { approvePayment } from '@/api/payments'
import { getMyReservations, holdSeats } from '@/api/reservations'
import { readEntryToken, saveEntryToken } from '@/auth/entryTokens'
import { sessionStore } from '@/auth/session'
import { expireMockAccessTokens, mockDb, toKstIso } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import {
  admitTo,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  requestStarted,
  SEAT_A1,
  signInAsDemo,
} from '@/test/api'

// 새로고침: 메모리 상태가 사라지고 sessionStorage에서 다시 읽는다
async function simulateReload() {
  const persisted = sessionStorage.getItem('iseonjwa.session')
  sessionStore.setState({ accessToken: null, refreshToken: null })
  if (persisted !== null) {
    sessionStorage.setItem('iseonjwa.session', persisted)
  }
  await sessionStore.persist.rehydrate()
}

async function captureError(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('apiRequest 공통 응답', () => {
  it('성공 응답에서 data만 꺼내 스키마로 검증한다', async () => {
    await signInAsDemo()

    const me = await getMe()

    expect(me).toEqual({ userId: 42, email: 'demo@iseonjwa.dev', nickname: '데모', role: 'USER' })
  })

  it('실패 응답은 코드·메시지·필드 오류·traceId를 담은 ApiError로 던진다', async () => {
    mockServer.use(
      http.post('/api/v1/users', () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
          errors: [{ field: 'email', reason: '이메일 형식이어야 합니다' }],
        }),
      ),
    )

    const error = await captureError(
      apiRequest({ method: 'POST', path: '/users', body: {}, schema: z.unknown(), auth: false }),
    )

    expect(error.status).toBe(400)
    expect(error.code).toBe('VALIDATION_FAILED')
    expect(error.fieldErrors).toEqual([{ field: 'email', reason: '이메일 형식이어야 합니다' }])
    expect(error.traceId).toMatch(/^[0-9a-f]{32}$/)
  })

  it('503의 Retry-After를 초 단위로 읽는다', async () => {
    mockServer.use(
      http.get('/api/v1/concerts', () =>
        fail(503, 'SERVICE_UNAVAILABLE', '일시적으로 서비스를 사용할 수 없습니다', {
          headers: { 'Retry-After': '3' },
        }),
      ),
    )

    const error = await captureError(
      apiRequest({ method: 'GET', path: '/concerts', schema: z.unknown(), auth: false }),
    )

    expect(error.retryAfterSeconds).toBe(3)
  })

  it('공통 실패 형식이 아닌 응답(중계 서버의 502 HTML)은 UNEXPECTED_RESPONSE다', async () => {
    mockServer.use(
      http.get(
        '/api/v1/concerts',
        () => new HttpResponse('<html>Bad Gateway</html>', { status: 502 }),
      ),
    )

    const error = await captureError(
      apiRequest({ method: 'GET', path: '/concerts', schema: z.unknown(), auth: false }),
    )

    expect(error.status).toBe(502)
    expect(error.code).toBe(CLIENT_ERROR_CODES.unexpectedResponse)
  })

  it('응답을 받지 못하면 상태 0의 NETWORK_ERROR다', async () => {
    mockServer.use(http.get('/api/v1/concerts', () => HttpResponse.error()))

    const error = await captureError(
      apiRequest({ method: 'GET', path: '/concerts', schema: z.unknown(), auth: false }),
    )

    expect(error.status).toBe(0)
    expect(error.code).toBe(CLIENT_ERROR_CODES.networkError)
  })

  it('성공 응답이 계약 스키마와 다르면 RESPONSE_SCHEMA_MISMATCH로 멈춘다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.get('/api/v1/users/me', () =>
        HttpResponse.json({ status: 200, message: '조회', data: { userId: '42' }, traceId: 'abc' }),
      ),
    )
    const consoleError = console.error
    console.error = () => {}

    try {
      const error = await captureError(getMe())
      expect(error.code).toBe(CLIENT_ERROR_CODES.responseSchemaMismatch)
      expect(error.traceId).toBe('abc')
    } finally {
      console.error = consoleError
    }
  })
})

describe('401 재발급', () => {
  it('Access 토큰이 만료되면 한 번 재발급하고 같은 요청을 다시 보낸다', async () => {
    await signInAsDemo()
    const oldRefreshToken = sessionStore.getState().refreshToken
    expireMockAccessTokens()
    const meRequests = recordRequests('/api/v1/users/me')
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    const me = await getMe()

    expect(me.userId).toBe(42)
    expect(refreshRequests).toHaveLength(1)
    expect(meRequests).toHaveLength(2)
    // 서버가 Refresh 토큰도 새로 주므로 저장한 값이 바뀐다
    expect(sessionStore.getState().refreshToken).not.toBe(oldRefreshToken)
  })

  it.each(['TOKEN_EXPIRED', 'UNAUTHORIZED', 'TOKEN_REVOKED'])(
    '401 %s도 코드와 상관없이 재발급하고 다시 보낸다',
    async (code) => {
      await signInAsDemo()
      mockServer.use(http.get('/api/v1/users/me', () => fail(401, code, '거절'), { once: true }))
      const refreshRequests = recordRequests('/api/v1/auth/refresh')

      const me = await getMe()

      expect(me.userId).toBe(42)
      expect(refreshRequests).toHaveLength(1)
    },
  )

  it('동시에 401을 받은 요청들은 재발급을 한 번만 부르고 모두 성공한다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    // Note: 서버는 같은 Refresh 토큰의 두 번째 재발급에 401 TOKEN_REVOKED를 준다(목도 같다).
    // 재발급이 두 번 나가면 하나가 실패해 세션이 지워지므로 이 테스트가 깨진다.
    const results = await Promise.all([getMe(), getMyReservations(), getMe()])

    expect(results.map((result) => 'userId' in result || 'content' in result)).toEqual([
      true,
      true,
      true,
    ])
    expect(refreshRequests).toHaveLength(1)
    expect(sessionStore.getState().refreshToken).not.toBeNull()
  })

  it('다른 요청이 이미 재발급했으면 늦게 온 401은 다시 재발급하지 않고 새 토큰으로 보낸다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    // 내 예매 조회는 늦게 401을 받는다. 그 사이 getMe가 재발급을 끝낸다
    mockServer.use(
      http.get(
        '/api/v1/reservations/me',
        async () => {
          await delay(50)
        },
        { once: true },
      ),
    )
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    const slow = getMyReservations()
    await getMe()
    await slow

    expect(refreshRequests).toHaveLength(1)
  })

  it('재발급이 4xx로 실패하면 세션을 지우고 401을 던진다. 다시 시도하지 않는다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    // 다른 기기에서 로그인해 Refresh 토큰이 바뀐 상황
    mockDb.refreshTokens.set(42, 'mock-refresh.42.999')
    const meRequests = recordRequests('/api/v1/users/me')

    const error = await captureError(getMe())

    expect(error.code).toBe('TOKEN_REVOKED')
    expect(meRequests).toHaveLength(1)
    expect(sessionStore.getState()).toEqual({ accessToken: null, refreshToken: null })
  })

  it('재발급이 503이면 Refresh 토큰을 지우지 않는다(Redis 장애 중에는 토큰이 아직 유효할 수 있다)', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    mockServer.use(
      http.post('/api/v1/auth/refresh', () =>
        fail(503, 'SERVICE_UNAVAILABLE', '일시적으로 서비스를 사용할 수 없습니다'),
      ),
    )

    const error = await captureError(getMe())

    expect(error.status).toBe(503)
    expect(sessionStore.getState().refreshToken).not.toBeNull()
  })

  it('새로고침 뒤 첫 요청은 401을 기다리지 않고 저장한 Refresh 토큰으로 먼저 재발급한다', async () => {
    await signInAsDemo()
    const refreshToken = sessionStore.getState().refreshToken
    await simulateReload()
    expect(sessionStore.getState()).toEqual({ accessToken: null, refreshToken })
    const meRequests = recordRequests('/api/v1/users/me')
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    await getMe()

    expect(refreshRequests).toHaveLength(1)
    expect(meRequests).toHaveLength(1)
  })

  it('재발급한 토큰으로 보낸 재시도도 401이면 세션을 지우고 끝난다(무한 반복하지 않는다)', async () => {
    await signInAsDemo()
    mockServer.use(
      http.get('/api/v1/users/me', () =>
        fail(401, 'UNAUTHORIZED', '유효한 인증 토큰이 필요합니다'),
      ),
    )
    const meRequests = recordRequests('/api/v1/users/me')
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    const error = await captureError(getMe())

    expect(error.status).toBe(401)
    expect(meRequests).toHaveLength(2)
    expect(refreshRequests).toHaveLength(1)
    expect(sessionStore.getState()).toEqual({ accessToken: null, refreshToken: null })
  })

  it('재발급을 기다리는 사이 로그아웃하면 늦게 온 재발급 결과로 세션을 되살리지 않는다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    mockServer.use(
      http.post(
        '/api/v1/auth/refresh',
        async () => {
          await delay(30)
        },
        { once: true },
      ),
      // 로그아웃 요청은 연결이 끊겨도 이 탭의 토큰은 지운다
      http.post('/api/v1/auth/logout', () => HttpResponse.error()),
    )

    const refreshInFlight = requestStarted('/api/v1/auth/refresh')
    const pendingMe = captureError(getMe())
    await refreshInFlight
    await logout()
    const error = await pendingMe

    expect(error.status).toBe(401)
    expect(sessionStore.getState()).toEqual({ accessToken: null, refreshToken: null })
  })

  it('재발급을 기다리는 사이 다시 로그인하면 늦게 실패한 재발급이 새 세션을 지우지 않는다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    mockServer.use(
      http.post(
        '/api/v1/auth/refresh',
        async () => {
          await delay(30)
          return fail(401, 'TOKEN_REVOKED', '토큰을 사용할 수 없습니다')
        },
        { once: true },
      ),
    )

    const refreshInFlight = requestStarted('/api/v1/auth/refresh')
    const pendingMe = captureError(getMe())
    await refreshInFlight
    await signInAsDemo()
    const newSession = sessionStore.getState()
    await pendingMe

    expect(sessionStore.getState()).toEqual(newSession)
    expect(newSession.refreshToken).not.toBeNull()
  })

  it('로그인하지 않았으면 요청을 보내지 않고 401을 던진다', async () => {
    const meRequests = recordRequests('/api/v1/users/me')

    const error = await captureError(getMe())

    expect(error.status).toBe(401)
    expect(meRequests).toHaveLength(0)
  })

  it('Refresh 토큰만 sessionStorage에 남기고 Access 토큰은 저장하지 않는다', async () => {
    await signInAsDemo()

    const stored = sessionStorage.getItem('iseonjwa.session') ?? ''

    expect(stored).toContain(sessionStore.getState().refreshToken)
    expect(stored).not.toContain(sessionStore.getState().accessToken)
  })
})

describe('재시도와 멱등키', () => {
  it('401 뒤 재시도는 처음과 같은 Idempotency-Key와 입장 토큰을 보낸다', async () => {
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    expireMockAccessTokens()
    const holdRequests = recordRequests(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/reservations`)
    const key = newIdempotencyKey()

    const hold = await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], key)

    expect(hold.requestId).toBe(key)
    expect(holdRequests).toHaveLength(2)
    expect(holdRequests.map((request) => request.headers.get('Idempotency-Key'))).toEqual([
      key,
      key,
    ])
    const entryTokens = holdRequests.map((request) => request.headers.get('X-Entry-Token'))
    expect(entryTokens[0]).not.toBeNull()
    expect(entryTokens[1]).toBe(entryTokens[0])
  })

  it('결제 요청이 401 뒤 재시도돼도 같은 멱등키와 본문을 보낸다', async () => {
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    const hold = await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey())
    if (hold.status !== 'HELD') throw new Error('선점이 HELD여야 한다')
    expireMockAccessTokens()
    const paymentRequests = recordRequests('/api/v1/payments')
    const key = newIdempotencyKey()

    const result = await approvePayment(
      { paymentToken: hold.paymentToken, amount: hold.totalAmount, method: 'CARD' },
      key,
    )

    expect(result.status).toBe('APPROVED')
    expect(paymentRequests.map((request) => request.headers.get('Idempotency-Key'))).toEqual([
      key,
      key,
    ])
    const bodies = await Promise.all(paymentRequests.map((request) => request.text()))
    expect(bodies[1]).toBe(bodies[0])
  })

  it('재발급을 기다리는 사이 호출한 쪽이 본문 객체를 바꿔도 재시도는 처음 본문을 보낸다', async () => {
    await signInAsDemo()
    await admitTo(ON_SALE_SCHEDULE_ID)
    expireMockAccessTokens()
    const holdRequests = recordRequests(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/reservations`)
    const body = { seatIds: [SEAT_A1] }

    const pending = apiRequest({
      method: 'POST',
      path: `/schedules/${ON_SALE_SCHEDULE_ID}/reservations`,
      body,
      schema: z.unknown(),
      idempotencyKey: newIdempotencyKey(),
      entryScheduleId: ON_SALE_SCHEDULE_ID,
    })
    body.seatIds.push(SEAT_A1 + 1)
    await pending

    const bodies = await Promise.all(holdRequests.map((request) => request.text()))
    expect(bodies).toEqual([`{"seatIds":[${SEAT_A1}]}`, `{"seatIds":[${SEAT_A1}]}`])
  })
})

describe('입장 토큰', () => {
  it('저장한 입장 토큰을 X-Entry-Token으로 붙인다', async () => {
    await signInAsDemo()
    const admitted = await admitTo(ON_SALE_SCHEDULE_ID)
    const seatRequests = recordRequests(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/seats`)

    await getScheduleSeats(ON_SALE_SCHEDULE_ID)

    expect(seatRequests[0].headers.get('X-Entry-Token')).toBe(admitted.entryToken)
  })

  it('403 ENTRY_TOKEN_INVALID를 받으면 저장한 입장 토큰을 지운다', async () => {
    await signInAsDemo()
    // 다른 회차의 토큰을 31회차 칸에 둔다. Gateway는 회차가 달라 403을 준다
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: `mock-entry.32.42.${Date.now() + 60_000}`,
      expiresAt: toKstIso(Date.now() + 60_000),
    })

    const error = await captureError(getScheduleSeats(ON_SALE_SCHEDULE_ID))

    expect(error.code).toBe('ENTRY_TOKEN_INVALID')
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).toBeNull()
  })

  it('브라우저 시계로 만료돼 보여도 요청을 막지 않고 Gateway 판단에 맡긴다', async () => {
    await signInAsDemo()
    const admitted = await admitTo(ON_SALE_SCHEDULE_ID)
    // 브라우저 시계가 빨라 이미 만료된 것처럼 보이는 상황
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: admitted.entryToken,
      expiresAt: toKstIso(Date.now() - 60_000),
    })

    const seats = await getScheduleSeats(ON_SALE_SCHEDULE_ID)

    expect(seats.scheduleId).toBe(ON_SALE_SCHEDULE_ID)
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)?.token).toBe(admitted.entryToken)
  })

  it('오래 걸린 요청의 403은 그 사이 새로 받은 입장 토큰을 지우지 않는다', async () => {
    await signInAsDemo()
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: `mock-entry.32.42.${Date.now() + 60_000}`,
      expiresAt: toKstIso(Date.now() + 60_000),
    })
    mockServer.use(
      http.get(
        `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/seats`,
        async () => {
          await delay(30)
        },
        { once: true },
      ),
    )

    const seatsInFlight = requestStarted(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/seats`)
    const pendingSeats = captureError(getScheduleSeats(ON_SALE_SCHEDULE_ID))
    await seatsInFlight
    const admitted = await admitTo(ON_SALE_SCHEDULE_ID)
    await pendingSeats

    expect(readEntryToken(ON_SALE_SCHEDULE_ID)?.token).toBe(admitted.entryToken)
  })

  it('저장한 입장 토큰이 없으면 요청 없이 403 ENTRY_TOKEN_INVALID로 끝난다', async () => {
    await signInAsDemo()
    const seatRequests = recordRequests(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/seats`)

    const error = await captureError(getScheduleSeats(ON_SALE_SCHEDULE_ID))

    expect(error.code).toBe('ENTRY_TOKEN_INVALID')
    expect(seatRequests).toHaveLength(0)
  })
})
