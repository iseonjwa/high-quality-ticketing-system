import { randomUUID } from 'node:crypto'
import type { TargetFixtures } from '../fixtures.ts'

// compose 대상: docker compose로 띄운 실제 스택(화면 nginx 8000 → Gateway)에 붙는다.
// API 호출은 Node fetch로 한다. Playwright의 request fixture를 쓰면 관리자 로그인 본문이 trace에 남기 때문이다.

const API = '/api/v1'
const DEMO_TITLE = 'E2E 시연 흐름 공연'
const SEAT_SECTION = 'A' // flow.ts의 pickOneSeat이 A구역을 고른다
const DEFAULT_PRICES = [
  { grade: 'VIP', price: 165000 },
  { grade: 'R', price: 143000 },
  { grade: 'S', price: 112000 },
]

export interface ComposeStack {
  baseUrl: string
  mockPgUrl: string
  concertId: number
  scheduleId: number
}

export interface ComposeTarget {
  fixtures: TargetFixtures
  dispose: () => Promise<void>
}

// worker마다 한 번: 공연과 판매 중 회차를 찾거나 만든다.
export async function prepareStack(): Promise<ComposeStack> {
  const baseUrl = (process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8000').replace(/\/$/, '')
  const mockPgUrl = (process.env.E2E_MOCK_PG_URL ?? 'http://127.0.0.1:8090').replace(/\/$/, '')
  const givenConcert = process.env.E2E_CONCERT_ID
  const givenSchedule = process.env.E2E_SCHEDULE_ID
  if (givenConcert && givenSchedule) {
    return {
      baseUrl,
      mockPgUrl,
      concertId: Number(givenConcert),
      scheduleId: Number(givenSchedule),
    }
  }
  const adminToken = await adminLogin(baseUrl)
  const concertId = givenConcert
    ? Number(givenConcert)
    : ((await findConcertId(baseUrl)) ?? (await createConcert(baseUrl, adminToken)))
  const scheduleId = givenSchedule
    ? Number(givenSchedule)
    : ((await findOnSaleScheduleId(baseUrl, concertId)) ??
      (await createSchedule(baseUrl, adminToken, concertId)))
  return { baseUrl, mockPgUrl, concertId, scheduleId }
}

// 테스트마다: 주인공(화면)과 경쟁자(API) 사용자를 만들고 빈 좌석 둘을 고른다.
export async function prepareTarget(stack: ComposeStack): Promise<ComposeTarget> {
  const tag = `${Date.now()}-${randomUUID().slice(0, 6)}`
  const hero = await signupUser(stack.baseUrl, `e2e-${tag}-1@iseonjwa.dev`)
  const rival = await signupUser(stack.baseUrl, `e2e-${tag}-2@iseonjwa.dev`)
  const rivalAccess = await loginUser(stack.baseUrl, rival)
  const entryToken = await admitToQueue(stack, rivalAccess)
  const picked = await pickFreeSeats(stack, rivalAccess, entryToken)

  const seatIds = new Map<string, number>()
  for (const seat of picked) seatIds.set(seat.name, seat.seatId)
  const faultOpen = await isFaultApiOpen(stack.mockPgUrl)
  let faultSet = false

  const fixtures: TargetFixtures = {
    account: hero,
    concertId: stack.concertId,
    seats: { first: picked[0].name, second: picked[1].name },
    // 경쟁자가 주인공보다 먼저 같은 좌석을 잡는다. 좌석 화면은 자동으로 다시 조회하지 않아 순서가 확정적이다.
    beforeHold: async (seat) => {
      const seatId = seatIds.get(seat)
      if (seatId === undefined) throw new Error(`알 수 없는 좌석: ${seat}`)
      await apiJson(stack.baseUrl, 'POST', `/schedules/${stack.scheduleId}/reservations`, {
        token: rivalAccess,
        headers: { 'X-Entry-Token': entryToken, 'Idempotency-Key': randomUUID() },
        body: { seatIds: [seatId] },
      })
    },
    // 결제 응답을 한 번 버려 '확인 중'을 만든다. 풀기는 dispose가 맡는다.
    beforePay: async () => {
      faultSet = true
      await setFault(stack.mockPgUrl, { target: 'APPROVE', mode: 'DROP_RESPONSE', count: 1 })
    },
    entryPath: () => '/login',
    skipReason: (scenario) =>
      scenario === 'payment-unknown' && !faultOpen
        ? 'Mock PG 장애 주입이 닫혀 있다. loadtest/k6/compose.mockpg-loadtest.yml을 얹어 띄운다'
        : null,
  }

  return {
    fixtures,
    dispose: async () => {
      if (faultSet) await setFault(stack.mockPgUrl, { target: 'APPROVE', mode: 'NONE' })
    },
  }
}

interface ApiOptions {
  token?: string
  headers?: Record<string, string>
  body?: unknown
  timeoutMs?: number
}

async function apiJson(baseUrl: string, method: string, path: string, options: ApiOptions = {}) {
  const headers: Record<string, string> = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.token) headers.Authorization = `Bearer ${options.token}`
  const response = await fetch(`${baseUrl}${API}${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(options.timeoutMs ?? 30_000),
  })
  const text = await response.text()
  if (!response.ok) {
    // 요청 본문(비밀번호 포함)은 오류 메시지에 넣지 않는다.
    throw new Error(`${method} ${path} ${response.status} ${text.slice(0, 200)}`)
  }
  const parsed = text ? (JSON.parse(text) as { data?: unknown }) : {}
  return (parsed.data ?? null) as Record<string, any> | null // eslint-disable-line @typescript-eslint/no-explicit-any
}

async function adminLogin(baseUrl: string): Promise<string> {
  const email = process.env.E2E_ADMIN_EMAIL
  const password = process.env.E2E_ADMIN_PASSWORD
  if (!email || !password) {
    throw new Error(
      '관리자 계정이 없다. E2E_ADMIN_EMAIL·E2E_ADMIN_PASSWORD를 주거나 저장소 루트 .env에 ADMIN_EMAIL·ADMIN_PASSWORD를 둔다. E2E_CONCERT_ID와 E2E_SCHEDULE_ID를 주면 관리자 단계를 건너뛴다',
    )
  }
  const data = await apiJson(baseUrl, 'POST', '/auth/login', { body: { email, password } })
  return String(data?.accessToken)
}

async function findConcertId(baseUrl: string): Promise<number | null> {
  for (let page = 0; ; page++) {
    const data = await apiJson(baseUrl, 'GET', `/concerts?page=${page}&size=100`)
    const content = (data?.content ?? []) as { concertId: number; title: string }[]
    const hit = content.find((concert) => concert.title === DEMO_TITLE)
    if (hit) return hit.concertId
    if (content.length < 100) return null
  }
}

async function createConcert(baseUrl: string, adminToken: string): Promise<number> {
  const data = await apiJson(baseUrl, 'POST', '/admin/concerts', {
    token: adminToken,
    body: { title: DEMO_TITLE, description: 'Playwright 시연 흐름 테스트용 공연' },
  })
  return Number(data?.concertId)
}

async function findOnSaleScheduleId(baseUrl: string, concertId: number): Promise<number | null> {
  const data = await apiJson(baseUrl, 'GET', `/concerts/${concertId}`)
  const schedules = (data?.schedules ?? []) as { scheduleId: number; saleStatus: string }[]
  return schedules.find((schedule) => schedule.saleStatus === 'ON_SALE')?.scheduleId ?? null
}

async function createSchedule(
  baseUrl: string,
  adminToken: string,
  concertId: number,
): Promise<number> {
  const now = Date.now()
  const created = await apiJson(baseUrl, 'POST', `/admin/concerts/${concertId}/schedules`, {
    token: adminToken,
    body: {
      venueId: Number(process.env.E2E_VENUE_ID ?? 1),
      startsAt: new Date(now + 7 * 86_400_000).toISOString(),
      openAt: new Date(now - 3_600_000).toISOString(),
      maxPerUser: 4,
    },
  })
  const scheduleId = Number(created?.scheduleId)
  // 좌석 수천~만 건을 한 번에 만들어 수 초 걸린다.
  await apiJson(baseUrl, 'POST', `/admin/schedules/${scheduleId}/seats`, {
    token: adminToken,
    body: { prices: DEFAULT_PRICES },
    timeoutMs: 120_000,
  })
  return scheduleId
}

async function signupUser(baseUrl: string, email: string) {
  const password = `E2e-${randomUUID()}`
  await apiJson(baseUrl, 'POST', '/users', {
    body: { email, password, nickname: email.split('@')[0].slice(-12) },
  })
  return { email, password }
}

async function loginUser(baseUrl: string, user: { email: string; password: string }) {
  const data = await apiJson(baseUrl, 'POST', '/auth/login', { body: user })
  return String(data?.accessToken)
}

// 대기열에 서서 READY가 될 때까지 기다린 뒤 입장 토큰을 받는다.
async function admitToQueue(stack: ComposeStack, access: string): Promise<string> {
  const base = `/schedules/${stack.scheduleId}/queue`
  let position = await apiJson(stack.baseUrl, 'POST', base, { token: access })
  const deadline = Date.now() + 90_000
  while (position?.status !== 'READY') {
    if (Date.now() > deadline) throw new Error('대기열 입장 시간 초과')
    await new Promise((resolve) =>
      setTimeout(resolve, Number(position?.nextPollAfterMillis ?? 1000)),
    )
    position = await apiJson(stack.baseUrl, 'GET', `${base}/me`, { token: access })
  }
  const admitted = await apiJson(stack.baseUrl, 'POST', `${base}/token`, { token: access })
  return String(admitted?.entryToken)
}

// 입장 토큰으로 좌석 지도를 읽어 A구역의 빈 좌석 둘을 고른다. 추첨석은 일반 예매로 고를 수 없어 뺀다.
async function pickFreeSeats(stack: ComposeStack, access: string, entryToken: string) {
  const data = await apiJson(stack.baseUrl, 'GET', `/schedules/${stack.scheduleId}/seats`, {
    token: access,
    headers: { 'X-Entry-Token': entryToken },
    timeoutMs: 60_000,
  })
  const sections = (data?.sections ?? []) as {
    section: string
    grade: string
    seats: { seatId: number; row: number; number: number; available: boolean }[]
  }[]
  const free = sections
    .filter((section) => section.section === SEAT_SECTION && section.grade !== 'LOTTERY')
    .flatMap((section) =>
      section.seats
        .filter((seat) => seat.available)
        .map((seat) => ({
          seatId: seat.seatId,
          name: `${section.section}구역 ${seat.row}열 ${seat.number}번`,
        })),
    )
  if (free.length < 2) {
    throw new Error(
      `${SEAT_SECTION}구역에 빈 좌석이 2개 미만이다. 새 회차를 만들거나 E2E_SCHEDULE_ID를 바꾼다`,
    )
  }
  return free.slice(0, 2)
}

async function isFaultApiOpen(mockPgUrl: string): Promise<boolean> {
  try {
    const response = await fetch(`${mockPgUrl}/mock-pg/faults`, {
      signal: AbortSignal.timeout(5_000),
    })
    return response.status === 200
  } catch {
    return false
  }
}

async function setFault(mockPgUrl: string, rule: Record<string, unknown>) {
  const response = await fetch(`${mockPgUrl}/mock-pg/faults`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rule),
    signal: AbortSignal.timeout(10_000),
  })
  if (!response.ok) throw new Error(`Mock PG 장애 주입 ${response.status}`)
}
