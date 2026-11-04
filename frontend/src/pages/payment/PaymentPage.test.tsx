import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { holdSeats, type HeldSeatHold } from '@/api/reservations'
import { paymentPath } from '@/app/paths'
import { routes } from '@/app/routes'
import { readHeldSeats, saveHeldSeats, type HeldSeat } from '@/auth/heldSeats'
import { mockDb, toKstIso, type MockReservation } from '@/mocks/db'
import { fail, ok } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { formatWon } from '@/lib/format'
import { paymentNavigationState } from '@/pages/payment/paymentNavigation'
import { queueNavigationState, readQueueConcert } from '@/pages/queue/queueNavigation'
import { readReservationNavigation } from '@/pages/reservations/reservationNavigation'
import {
  admitTo,
  CONCERT_ID,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  SEAT_A1,
  SEAT_A2,
  signInAsDemo,
} from '@/test/api'

const CONCERT = {
  concertId: CONCERT_ID,
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}
const PAYMENTS_API = '/api/v1/payments'
const TIMER_TEXT = /결제 가능\s*0[45]:\d\d/

beforeEach(async () => {
  await signInAsDemo()
})

afterEach(() => {
  vi.useRealTimers()
})

interface Held {
  hold: HeldSeatHold
  seats: HeldSeat[]
}

// 목 서버에 실제로 선점을 만든다. 결제 토큰·금액·예매 번호가 목 서버와 맞는다
async function hold(): Promise<Held> {
  await admitTo(ON_SALE_SCHEDULE_ID)
  const result = await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1, SEAT_A2], newIdempotencyKey())
  if (result.status !== 'HELD') {
    throw new Error('HELD여야 한다')
  }
  const seats = mockDb.seats
    .filter((seat) => result.seatIds.includes(seat.seatId))
    .map(({ seatId, section, grade, price, row, number }) => ({
      seatId,
      section,
      grade,
      price,
      row,
      number,
    }))
  return { hold: result, seats }
}

function reservationOf(reservationId: number): MockReservation {
  return mockDb.reservations.get(reservationId)!
}

interface Visit {
  pathname: string
  state: unknown
  action: string
}

const resultPath = (held: Held) => `/reservations/${held.hold.reservationId}`
const QUEUE_PATH = `/schedules/${ON_SALE_SCHEDULE_ID}/queue`

const visitTo = (visited: Visit[], pathname: string) =>
  visited.find((visit) => visit.pathname === pathname)

interface RenderOptions {
  // 좌석 화면이 넘긴 이동 값. 없으면 새 탭으로 주소만 연 경우다
  navigation?: {
    held: Held
    concert?: typeof CONCERT | null
    clockOffsetMillis?: number | null
  }
  reservationId?: number | string
}

// 조회 재시도를 끈 클라이언트로 그려 실패 화면을 바로 본다
function renderPayment({ navigation, reservationId }: RenderOptions) {
  const id = reservationId ?? navigation?.held.hold.reservationId
  const state = navigation
    ? paymentNavigationState({
        hold: navigation.held.hold,
        seats: navigation.held.seats,
        concert: navigation.concert === undefined ? CONCERT : navigation.concert,
        clockOffsetMillis: navigation.clockOffsetMillis ?? null,
      })
    : null
  const router = createMemoryRouter(routes, {
    initialEntries: [
      {
        pathname: typeof id === 'string' ? `/reservations/${id}/payment` : paymentPath(id!),
        state,
      },
    ],
  })
  // 입장 토큰이 남은 대기열은 곧바로 좌석 화면으로 다시 보내므로, 거쳐 간 위치를 따로 모은다
  const visited: Visit[] = []
  router.subscribe(({ location, historyAction }) =>
    visited.push({ pathname: location.pathname, state: location.state, action: historyAction }),
  )
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, visited }
}

const payButton = () => screen.findByRole('button', { name: /원 결제하기$/ })

async function clickPay() {
  await userEvent.click(await payButton())
}

async function advance(millis: number) {
  await act(() => vi.advanceTimersByTimeAsync(millis))
}

// 조건이 될 때까지 가짜 시계를 나눠 앞으로 돌린다. 한 번에 건너뛰면 사이의 요청이 끼어들지 못한다
async function advanceUntil(done: () => boolean, stepMillis = 1000, limitMillis = 120_000) {
  for (let elapsed = 0; elapsed <= limitMillis; elapsed += stepMillis) {
    if (done()) {
      return
    }
    await advance(stepMillis)
  }
  throw new Error('가짜 시계를 끝까지 돌려도 조건이 되지 않았다')
}

// 예매 조회 첫 요청(화면이 하는 조회)은 목에 맡기고, 그다음부터는 예매를 그 상태로 바꿔 답하게 한다
function endReservationAfterFirstRead(reservationId: number, status: MockReservation['status']) {
  let calls = 0
  mockServer.use(
    http.get(`/api/v1/reservations/${reservationId}`, () => {
      calls += 1
      if (calls > 1) {
        const reservation = reservationOf(reservationId)
        reservation.status = status
        reservation.confirmAfterChecks = null
      }
      return undefined
    }),
  )
}

function declinedResponse(reservationId: number, reasonCode: string) {
  return http.post(PAYMENTS_API, () =>
    ok({ paymentId: 5001, reservationId, status: 'DECLINED', reasonCode }),
  )
}

// 알림이 뜨면 초점이 가는 자리(알림을 감싼 칸)
function alertSlotOf(title: string) {
  return screen.getByText(title).closest('[tabindex="-1"]')
}

describe('PaymentPage 결제 대상', () => {
  it('좌석 화면이 넘긴 값으로 공연·결제 수단·좌석·금액·결제 가능 시간을 보이고 예매는 한 번만 조회한다', async () => {
    const held = await hold()
    const reservationReads = recordRequests(`/api/v1/reservations/${held.hold.reservationId}`)
    renderPayment({ navigation: { held } })

    expect(await payButton()).toHaveTextContent(formatWon(held.hold.totalAmount))
    expect(screen.getByRole('heading', { level: 1, name: CONCERT.title })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '결제' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /신용·체크카드/ })).toBeChecked()
    expect(screen.getByText(held.hold.reservationNo)).toBeInTheDocument()
    const summary = screen.getByRole('complementary')
    const items = within(summary).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('A구역 1열 1번')
    expect(items[1]).toHaveTextContent('A구역 1열 2번')
    expect(within(summary).getByText(formatWon(held.hold.totalAmount))).toBeInTheDocument()
    const timers = screen.getAllByRole('timer')
    timers.forEach((timer) => expect(timer).toHaveTextContent(TIMER_TEXT))
    await waitFor(() => expect(reservationReads).toHaveLength(1))
  })

  it('이동 값 없이 주소만 열면 예매 조회로 만들고, 공연 카드 없이 보이지 않는 제목 결제를 둔다', async () => {
    const held = await hold()
    renderPayment({ reservationId: held.hold.reservationId })

    expect(await payButton()).toHaveTextContent(formatWon(held.hold.totalAmount))
    expect(screen.getByRole('heading', { level: 1, name: '결제' })).toBeInTheDocument()
    expect(screen.queryByText(CONCERT.title)).not.toBeInTheDocument()
    expect(screen.getByText(held.hold.reservationNo)).toBeInTheDocument()
    expect(within(screen.getByRole('complementary')).getAllByRole('listitem')).toHaveLength(2)
    // 결제 가능 시각은 선점 만료 10초 전이다
    screen.getAllByRole('timer').forEach((timer) => expect(timer).toHaveTextContent(TIMER_TEXT))
  })

  it('예매 조회는 한 번만 받아 다시 받지 않고, 결제는 처음 받은 토큰으로 보낸다', async () => {
    const held = await hold()
    const id = held.hold.reservationId
    const tokens: string[] = []
    mockServer.events.on('response:mocked', async ({ request, response }) => {
      if (new URL(request.url).pathname === `/api/v1/reservations/${id}`) {
        const body = (await response.clone().json()) as { data: { paymentToken: string } }
        tokens.push(body.data.paymentToken)
      }
    })
    const reads = recordRequests(`/api/v1/reservations/${id}`)
    const payments = recordRequests(PAYMENTS_API)
    renderPayment({ reservationId: id })
    await payButton()

    // 다시 받을 계기(연결 복구)가 와도 조회를 반복하지 않는다. 조회마다 결제 토큰이 새로 서명된다
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(reads).toHaveLength(1)
    expect(tokens).toHaveLength(1)

    mockServer.use(http.post(PAYMENTS_API, () => fail(503, 'PG_UNAVAILABLE', '장애')))
    await clickPay()
    await waitFor(() => expect(payments).toHaveLength(1))
    const body = (await payments[0].json()) as { paymentToken: string }
    expect(body.paymentToken).toBe(tokens[0])
  })

  it('예매가 이미 확정이면 결제 화면 대신 결과 경로로 바꿔 들어가며 공연 요약을 넘긴다', async () => {
    const held = await hold()
    reservationOf(held.hold.reservationId).status = 'CONFIRMED'
    const payments = recordRequests(PAYMENTS_API)
    const { router } = renderPayment({ navigation: { held } })

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/reservations/${held.hold.reservationId}`),
    )
    expect(router.state.historyAction).toBe('REPLACE')
    expect(readReservationNavigation(router.state.location.state)).toEqual({
      concert: CONCERT,
      fromPayment: true,
    })
    expect(payments).toHaveLength(0)
  })

  it('이동 값이 없어도 확정된 예매는 결과 경로로 바꿔 들어가고 공연 요약은 비어 있다', async () => {
    const held = await hold()
    reservationOf(held.hold.reservationId).status = 'CONFIRMED'
    const { router } = renderPayment({ reservationId: held.hold.reservationId })

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/reservations/${held.hold.reservationId}`),
    )
    expect(router.state.historyAction).toBe('REPLACE')
    expect(readReservationNavigation(router.state.location.state)).toEqual({
      concert: null,
      fromPayment: true,
    })
  })

  it.each(['EXPIRED', 'RELEASED'] as const)(
    '좌석 화면 값이 있어도 예매가 %s면 결제할 수 없다고 알리고 결제 버튼을 막으며, 좌석 다시 고르기는 대기열로 바꿔 보낸다',
    async (status) => {
      const held = await hold()
      saveHeldSeats(ON_SALE_SCHEDULE_ID, held)
      reservationOf(held.hold.reservationId).status = status
      const payments = recordRequests(PAYMENTS_API)
      const { visited } = renderPayment({ navigation: { held } })

      expect(await screen.findByText('결제할 수 없는 예매예요')).toBeInTheDocument()
      expect(await payButton()).toBeDisabled()
      expect(screen.queryAllByRole('timer')).toHaveLength(0)
      expect(screen.queryByText('결제 가능 시간이 지났어요')).not.toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: '좌석 다시 고르기' }))

      await waitFor(() => expect(visitTo(visited, QUEUE_PATH)).toBeDefined())
      expect(visitTo(visited, QUEUE_PATH)?.action).toBe('REPLACE')
      expect(readQueueConcert(visitTo(visited, QUEUE_PATH)?.state)).toEqual(CONCERT)
      expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).toBeNull()
      expect(payments).toHaveLength(0)
    },
  )

  it('이동 값이 없고 만료된 예매면 결제할 수 없다고 알리고 좌석 다시 고르기로 대기열에 보낸다', async () => {
    const held = await hold()
    reservationOf(held.hold.reservationId).status = 'EXPIRED'
    const { visited } = renderPayment({ reservationId: held.hold.reservationId })

    expect(await screen.findByText('결제할 수 없는 예매예요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '좌석 다시 고르기' }))

    await waitFor(() => expect(visitTo(visited, QUEUE_PATH)).toBeDefined())
    expect(visitTo(visited, QUEUE_PATH)?.action).toBe('REPLACE')
  })

  it('이동 값이 없고 HELD여도 결제 토큰을 받지 못하면(결제 가능 시간 뒤) 결제할 수 없다고 알린다', async () => {
    const held = await hold()
    // 선점 만료 10초 전이 이미 지났다
    reservationOf(held.hold.reservationId).holdExpiresAtMillis = Date.now() + 5_000
    renderPayment({ reservationId: held.hold.reservationId })

    expect(await screen.findByText('결제할 수 없는 예매예요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
  })

  it('없는 예매면 찾는 예매가 없다고 알리고 내 예매 보기를 준다', async () => {
    renderPayment({ reservationId: 987654 })

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 예매 보기' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
  })

  it('주소의 예매 번호가 숫자가 아니면 조회 없이 같은 안내를 보인다', async () => {
    const reads = recordRequests('/api/v1/reservations/abc')
    renderPayment({ reservationId: 'abc' })

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(reads).toHaveLength(0)
  })
})

describe('PaymentPage 결제 가능 시간 칩', () => {
  it('서버 시계로 이미 지났으면 처음부터 시간이 지났다고 알리고 결제 버튼을 막으며 요청을 보내지 않는다', async () => {
    const held = await hold()
    const payments = recordRequests(PAYMENTS_API)
    // 서버 시각이 브라우저보다 10분 빠르다. 서버 기준으로 결제 가능 시간이 이미 지났다
    renderPayment({ navigation: { held, clockOffsetMillis: 10 * 60_000 } })

    expect(await screen.findByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
    expect(await payButton()).toBeDisabled()
    expect(screen.getAllByRole('timer').length).toBeGreaterThan(0)
    screen.getAllByRole('timer').forEach((timer) => expect(timer).toHaveTextContent('00:00'))
    await userEvent.click(screen.getByRole('button', { name: /결제하기$/ }))
    expect(payments).toHaveLength(0)
  })

  it('서버 시계 차이로 남았으면 브라우저 시계로 지났어도 막지 않는다', async () => {
    const held = await hold()
    const browserExpired = {
      ...held,
      hold: { ...held.hold, paymentTokenExpiresAt: toKstIso(Date.now() - 60_000) },
    }
    // 서버 시각은 브라우저보다 5분 느리다. 서버 기준으로는 4분쯤 남는다
    renderPayment({ navigation: { held: browserExpired, clockOffsetMillis: -5 * 60_000 } })

    expect(await payButton()).toBeEnabled()
    expect(screen.queryByText('결제 가능 시간이 지났어요')).not.toBeInTheDocument()
    screen.getAllByRole('timer').forEach((timer) => expect(timer).toHaveTextContent(/0[34]:\d\d/))
  })

  it('시계 차이가 없으면 브라우저 시계로 0이 돼도 막지 않고 서버 403을 따라 시간이 지났다고 알린다', async () => {
    const held = await hold()
    const browserExpired = {
      ...held,
      hold: { ...held.hold, paymentTokenExpiresAt: toKstIso(Date.now() - 60_000) },
    }
    renderPayment({ navigation: { held: browserExpired, clockOffsetMillis: null } })

    const button = await payButton()
    expect(button).toBeEnabled()
    expect(screen.queryByText('결제 가능 시간이 지났어요')).not.toBeInTheDocument()
    screen.getAllByRole('timer').forEach((timer) => expect(timer).toHaveTextContent('00:00'))

    mockServer.use(http.post(PAYMENTS_API, () => fail(403, 'PAYMENT_TOKEN_INVALID', '토큰')))
    await userEvent.click(button)

    expect(await screen.findByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /원 결제하기$/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '좌석 다시 고르기' })).toBeInTheDocument()
  })

  it('결제 요청 중·확인 중에는 보이고 승인되거나 거절되면 숨긴다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    renderPayment({ navigation: { held } })

    await clickPay()
    expect(await screen.findByText('결제를 확인하고 있어요')).toBeInTheDocument()
    expect(screen.getAllByRole('timer').length).toBeGreaterThan(0)

    await advanceUntil(() => screen.queryByText('결제가 승인됐어요') !== null)
    expect(screen.queryAllByRole('timer')).toHaveLength(0)
  })
})

describe('PaymentPage 결제하기', () => {
  it('누르면 본문·멱등키로 보내고 저장한 선점을 지우며, 요청 중에는 결제하는 중으로 바꿔 한 번만 보낸다', async () => {
    const held = await hold()
    saveHeldSeats(ON_SALE_SCHEDULE_ID, held)
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    mockServer.use(
      http.post(PAYMENTS_API, async () => {
        await gate
        return undefined
      }),
    )
    const payments = recordRequests(PAYMENTS_API)
    renderPayment({ navigation: { held } })

    await clickPay()

    const busy = await screen.findByRole('button', { name: '결제하는 중' })
    expect(busy).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(busy)
    await waitFor(() => expect(payments).toHaveLength(1))
    expect(await payments[0].json()).toEqual({
      paymentToken: held.hold.paymentToken,
      amount: held.hold.totalAmount,
      method: 'CARD',
    })
    expect(payments[0].headers.get('Idempotency-Key')).toBeTruthy()
    // 결제를 시작하면 좌석 화면이 이 선점으로 대화상자를 다시 띄우지 않는다
    expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).toBeNull()
    release()
  })

  it('승인되면 결제가 승인됐다고 알리고, 예매가 확정되면 결과 경로로 바꿔 들어가며 공연 요약을 넘긴다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const held = await hold()
    const { router } = renderPayment({ navigation: { held } })

    await clickPay()
    expect(await screen.findByText('결제가 승인됐어요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()

    await advanceUntil(() => router.state.location.pathname === resultPath(held))
    expect(router.state.location.pathname).toBe(`/reservations/${held.hold.reservationId}`)
    expect(router.state.historyAction).toBe('REPLACE')
    expect(readReservationNavigation(router.state.location.state)).toEqual({
      concert: CONCERT,
      fromPayment: true,
    })
  })

  it('이동 값 없이 시작해도 확정되면 결과 경로로 가고 공연 요약은 비어 있다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const held = await hold()
    const { router } = renderPayment({ reservationId: held.hold.reservationId })

    await clickPay()
    await advanceUntil(() => router.state.location.pathname === resultPath(held))

    expect(router.state.location.pathname).toBe(`/reservations/${held.hold.reservationId}`)
    expect(readReservationNavigation(router.state.location.state)).toEqual({
      concert: null,
      fromPayment: true,
    })
  })

  it('결과를 기다리는 중에는 확인 중 표시만 두고, 승인되면 승인 표시로 바꾼 뒤 결과 경로로 간다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const { router } = renderPayment({ navigation: { held } })

    await clickPay()

    const checking = await screen.findByRole('status')
    expect(checking).toHaveTextContent('결제를 확인하고 있어요')
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 결제하기' })).not.toBeInTheDocument()

    await advanceUntil(() => screen.queryByText('결제가 승인됐어요') !== null)
    await advanceUntil(() => router.state.location.pathname === resultPath(held))
    expect(router.state.location.pathname).toBe(`/reservations/${held.hold.reservationId}`)
  })
})

describe('PaymentPage 거절·장애·수동 확인', () => {
  it('거절되면 사유별 첫 문장과 좌석 다시 고르기를 보이고 초점을 알림으로 옮긴다', async () => {
    setMockScenario({ payment: 'declined' })
    const held = await hold()
    saveHeldSeats(ON_SALE_SCHEDULE_ID, held)
    const { visited } = renderPayment({ navigation: { held } })

    await clickPay()

    const title = await screen.findByText('결제가 거절됐어요')
    expect(title.closest('[role="alert"]')).toHaveTextContent('카드 한도가 부족해요.')
    expect(title.closest('[role="alert"]')).toHaveTextContent('잡아 둔 좌석은 풀렸으니')
    expect(alertSlotOf('결제가 거절됐어요')).toHaveFocus()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 결제하기' })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('timer')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: '좌석 다시 고르기' }))
    await waitFor(() => expect(visitTo(visited, QUEUE_PATH)).toBeDefined())
    expect(visitTo(visited, QUEUE_PATH)?.action).toBe('REPLACE')
    expect(readQueueConcert(visitTo(visited, QUEUE_PATH)?.state)).toEqual(CONCERT)
    expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).toBeNull()
  })

  it.each([
    ['CARD_DECLINED', '카드사가 결제를 거절했어요.'],
    ['INSUFFICIENT_LIMIT', '카드 한도가 부족해요.'],
    ['TOKEN_EXPIRED', '결제 가능 시간이 지나 결제하지 못했어요.'],
  ])('거절 사유 %s의 첫 문장을 보인다', async (reasonCode, sentence) => {
    const held = await hold()
    mockServer.use(declinedResponse(held.hold.reservationId, reasonCode))
    renderPayment({ navigation: { held } })

    await clickPay()

    const alert = (await screen.findByText('결제가 거절됐어요')).closest('[role="alert"]')
    expect(alert).toHaveTextContent(sentence)
  })

  it('모르는 사유 코드면 첫 문장 없이 좌석 안내만 보인다', async () => {
    const held = await hold()
    mockServer.use(declinedResponse(held.hold.reservationId, 'SOMETHING_NEW'))
    renderPayment({ navigation: { held } })

    await clickPay()

    const alert = (await screen.findByText('결제가 거절됐어요')).closest('[role="alert"]')
    expect(alert).toHaveTextContent(/^결제가 거절됐어요잡아 둔 좌석은 풀렸으니/)
  })

  it('조회로 거절을 알게 되면(사유 없음) 첫 문장 없이 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    mockServer.use(
      http.get('/api/v1/payments/:paymentId', ({ params }) =>
        ok({
          paymentId: Number(params.paymentId),
          reservationId: held.hold.reservationId,
          status: 'DECLINED',
          amount: held.hold.totalAmount,
          approvedAt: null,
          refundedAt: null,
        }),
      ),
    )
    renderPayment({ navigation: { held } })

    await clickPay()
    await advanceUntil(() => screen.queryByText('결제가 거절됐어요') !== null)

    const alert = screen.getByText('결제가 거절됐어요').closest('[role="alert"]')
    expect(alert).toHaveTextContent(/^결제가 거절됐어요잡아 둔 좌석은 풀렸으니/)
  })

  it('이미 끝난 결제(409 PAYMENT_ALREADY_CLOSED)면 안내와 좌석 다시 고르기를 보인다', async () => {
    const held = await hold()
    mockServer.use(
      http.post(PAYMENTS_API, () => fail(409, 'PAYMENT_ALREADY_CLOSED', '이미 종료된 결제')),
    )
    renderPayment({ navigation: { held } })

    await clickPay()

    expect(await screen.findByText('이 예매의 결제는 이미 끝났어요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '좌석 다시 고르기' })).toBeInTheDocument()
    expect(alertSlotOf('이 예매의 결제는 이미 끝났어요')).toHaveFocus()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
  })

  it('결제사 장애면 안내하고 다시 결제하기는 같은 멱등키로 보내며, 결제 가능 시간은 계속 보인다', async () => {
    setMockScenario({ payment: 'pg-unavailable' })
    const held = await hold()
    const payments = recordRequests(PAYMENTS_API)
    renderPayment({ navigation: { held } })

    await clickPay()

    expect(await screen.findByText('결제사 연결이 원활하지 않아요')).toBeInTheDocument()
    expect(alertSlotOf('결제사 연결이 원활하지 않아요')).toHaveFocus()
    expect(screen.getAllByRole('timer').length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: '다시 결제하기' }))
    await waitFor(() => expect(payments).toHaveLength(2))

    const keys = payments.map((request) => request.headers.get('Idempotency-Key'))
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
    expect(await screen.findByRole('button', { name: '다시 결제하기' })).toBeInTheDocument()
  })

  it('그 밖의 오류면 오류 안내와 다시 결제하기를 보이고 같은 멱등키로 다시 보낸다', async () => {
    const held = await hold()
    mockServer.use(http.post(PAYMENTS_API, () => fail(400, 'VALIDATION_FAILED', '요청 값 오류')))
    const payments = recordRequests(PAYMENTS_API)
    renderPayment({ navigation: { held } })

    await clickPay()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('결제가 거절됐어요')).not.toBeInTheDocument()
    expect(screen.getAllByRole('timer').length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: '다시 결제하기' }))
    await waitFor(() => expect(payments).toHaveLength(2))
    expect(payments[1].headers.get('Idempotency-Key')).toBe(
      payments[0].headers.get('Idempotency-Key'),
    )
  })

  it('운영자 확인이 필요하면 안내하고 내 예매 보기 링크만 준다', async () => {
    setMockScenario({ payment: 'manual' })
    const held = await hold()
    renderPayment({ navigation: { held } })

    await clickPay()

    expect(await screen.findByText('결제 확인이 늦어지고 있어요')).toBeInTheDocument()
    expect(alertSlotOf('결제 확인이 늦어지고 있어요')).toHaveFocus()
    expect(screen.getByRole('link', { name: '내 예매 보기' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('timer')).toHaveLength(0)
  })
})

describe('PaymentPage 확정 확인 뒤 안내', () => {
  it('승인 뒤 예매가 만료되면 환불 안내와 내 예매 보기를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const held = await hold()
    endReservationAfterFirstRead(held.hold.reservationId, 'EXPIRED')
    renderPayment({ navigation: { held } })

    await clickPay()
    await advanceUntil(() => screen.queryByText('예매가 확정되지 않았어요') !== null)

    expect(screen.getByText(/결제 금액은 환불돼요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 예매 보기' })).toBeInTheDocument()
    expect(alertSlotOf('예매가 확정되지 않았어요')).toHaveFocus()
  })

  it('승인 뒤 예매가 풀리거나 취소되면 확정되지 않았다고 알리고 내 예매 보기를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const held = await hold()
    endReservationAfterFirstRead(held.hold.reservationId, 'RELEASED')
    renderPayment({ navigation: { held } })

    await clickPay()
    await advanceUntil(() => screen.queryByText('예매가 확정되지 않았어요') !== null)

    expect(screen.getByText(/결제한 금액이 있으면 환불돼요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 예매 보기' })).toBeInTheDocument()
  })

  it('승인됐는데 30초가 지나도 확정되지 않으면 결제는 승인됐다고 알리고 내 예매 보기를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ payment: 'slow-confirm' })
    const held = await hold()
    renderPayment({ navigation: { held } })

    await clickPay()
    expect(await screen.findByText('결제가 승인됐어요')).toBeInTheDocument()
    await advanceUntil(() => screen.queryByText('결제는 승인됐어요') !== null)

    expect(screen.getByRole('link', { name: '내 예매 보기' })).toBeInTheDocument()
    expect(screen.queryByText('결제 결과를 아직 확인하지 못했어요')).not.toBeInTheDocument()
    expect(alertSlotOf('결제는 승인됐어요')).toHaveFocus()
    expect(screen.queryByRole('button', { name: /결제하기$/ })).not.toBeInTheDocument()
  })

  it('결과 모름 재전송이 한도에 닿으면 결과를 아직 확인하지 못했다고 알린다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const held = await hold()
    mockServer.use(
      http.post(PAYMENTS_API, () => fail(504, 'INTERNAL_SERVER_ERROR', '서버 내부 오류')),
    )
    const payments = recordRequests(PAYMENTS_API)
    renderPayment({ navigation: { held } })

    await clickPay()
    expect(await screen.findByText('결제를 확인하고 있어요')).toBeInTheDocument()
    await advanceUntil(
      () => screen.queryByText('결제 결과를 아직 확인하지 못했어요') !== null,
      2000,
    )

    expect(screen.queryByText('결제는 승인됐어요')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 예매 보기' })).toBeInTheDocument()
    // 처음 요청과 재전송 8번은 모두 같은 멱등키다
    expect(payments).toHaveLength(9)
    const keys = new Set(payments.map((request) => request.headers.get('Idempotency-Key')))
    expect(keys.size).toBe(1)
  })
})

describe('PaymentPage 좌석 화면 값', () => {
  it('이동 값이 결제용이 아니면(공연 요약뿐) 무시하고 예매 조회로 만든다', async () => {
    const held = await hold()
    const router = createMemoryRouter(routes, {
      initialEntries: [
        {
          pathname: paymentPath(held.hold.reservationId),
          state: queueNavigationState(CONCERT),
        },
      ],
    })
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    expect(await payButton()).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '결제' })).toBeInTheDocument()
  })
})
