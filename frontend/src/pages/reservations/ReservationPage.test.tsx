import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  reservationQueryKeys,
  type ReservationDetail,
  type ReservationStatus,
} from '@/api/reservations'
import { routes } from '@/app/routes'
import { savePaymentAttempt } from '@/auth/paymentAttempts'
import { mockDb, toKstIso } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { formatDateTime } from '@/lib/format'
import {
  readReservationHeading,
  reservationNavigationState,
} from '@/pages/reservations/reservationNavigation'
import { recordRequests, SEAT_A1, SEAT_A2, signInAsDemo } from '@/test/api'
import { seedReservation } from '@/test/reservations'

const HEADING = {
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀' as string | null,
}
const HEADING_LINE = `${HEADING.title} · ${formatDateTime(HEADING.startsAt)} · ${HEADING.venueName}`

beforeEach(async () => {
  await signInAsDemo()
})

afterEach(() => {
  vi.useRealTimers()
})

interface RenderOptions {
  variant?: 'complete' | 'detail'
  heading?: typeof HEADING | null
  // 화면을 그리기 전에 조회 캐시에 넣어 둘 값(결제 화면이 확정 뒤 넣고 오는 값)
  primed?: ReservationDetail
}

// 조회 재시도를 끈 클라이언트로 그려 실패 화면을 바로 본다
function renderReservation(reservationId: number | string, options: RenderOptions = {}) {
  const { variant = 'detail', heading = null, primed } = options
  const pathname =
    variant === 'complete'
      ? `/reservations/${reservationId}/complete`
      : `/reservations/${reservationId}`
  const router = createMemoryRouter(routes, {
    initialEntries: [{ pathname, state: heading ? reservationNavigationState(heading) : null }],
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  if (primed) {
    queryClient.setQueryData(reservationQueryKeys.detail(primed.reservationId), primed)
  }
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

function linkNamed(name: string) {
  return screen.getByRole('link', { name })
}

// 창이 다시 보이는 순간(TanStack Query가 신선하지 않은 조회를 다시 받는 계기)
function returnToWindow() {
  act(() => {
    window.dispatchEvent(new Event('visibilitychange'))
  })
}

// 가짜 시계를 나눠 돌린다. 한 번에 건너뛰면 사이의 다시 받기 요청이 끼어들지 못한다
async function advance(millis: number, stepMillis = 1000) {
  for (let elapsed = 0; elapsed < millis; elapsed += stepMillis) {
    await act(() => vi.advanceTimersByTimeAsync(Math.min(stepMillis, millis - elapsed)))
  }
}

// 첫 호출은 목에 맡기고, 그다음부터 지정한 응답을 준다(undefined면 목에 맡긴다)
function respondFromSecondRead(
  reservationId: number,
  respond: (call: number) => Response | undefined,
) {
  let calls = 0
  mockServer.use(
    http.get(`/api/v1/reservations/${reservationId}`, () => {
      calls += 1
      return calls === 1 ? undefined : respond(calls)
    }),
  )
}

describe('ReservationPage 결과 화면(complete) 확정', () => {
  it('확정 머리(공연 한 줄)와 좌석별 티켓 번호·상태 배지·이동 버튼을 보인다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId, { variant: 'complete', heading: HEADING })

    expect(
      await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' }),
    ).toBeInTheDocument()
    expect(screen.getByText(HEADING_LINE)).toBeInTheDocument()
    const tickets = screen.getByRole('region', { name: '티켓 2매' })
    const items = within(tickets).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('A구역 1열 1번')
    expect(items[0]).toHaveTextContent(`티켓 번호 T${reservation.reservationId}-${SEAT_A1}`)
    expect(items[1]).toHaveTextContent('A구역 1열 2번')
    expect(items[1]).toHaveTextContent(`티켓 번호 T${reservation.reservationId}-${SEAT_A2}`)
    // 결과 화면은 머리에 상태 배지가 없어 카드에 배지를 둔다
    expect(within(tickets).getByText('예매 확정')).toBeInTheDocument()
    expect(within(tickets).getByText('330,000원')).toBeInTheDocument()
    expect(within(tickets).getByText(reservation.reservationNo)).toBeInTheDocument()
    expect(linkNamed('내 예매 목록')).toHaveAttribute('href', '/me/reservations')
    expect(linkNamed('다른 공연 보기')).toHaveAttribute('href', '/')
  })

  it('공연 값이 없으면 머리에 예매 번호를 보인다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId, { variant: 'complete' })

    await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' })
    expect(screen.getByText(`예매 번호 ${reservation.reservationNo}`)).toBeInTheDocument()
    expect(screen.queryByText(HEADING.title)).not.toBeInTheDocument()
  })

  it('공연장 이름이 없는 값(내 예매 목록에서 넘김)이면 제목과 일시만 한 줄로 보인다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId, {
      variant: 'complete',
      heading: { ...HEADING, venueName: null },
    })

    await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' })
    expect(
      screen.getByText(`${HEADING.title} · ${formatDateTime(HEADING.startsAt)}`),
    ).toBeInTheDocument()
  })

  it('헤더 예매 단계가 "완료"를 현재 단계로 표시한다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId, { variant: 'complete' })

    const steps = await screen.findByRole('list', { name: '예매 단계' })
    expect(within(steps).getByRole('listitem', { current: 'step' })).toHaveTextContent('완료')
  })

  it('결제 화면이 넣은 확정 예매가 캐시에 있으면 조회하지 않고(확정은 60초 동안 신선) 확정을 보인다', async () => {
    // 서버(목)는 아직 HELD로 답한다. 보이는 확정은 캐시에서 온 것이고, 조회가 나가면 HELD로 바뀐다
    const reservation = seedReservation({ status: 'HELD' })
    const reads = recordRequests(`/api/v1/reservations/${reservation.reservationId}`)
    const primed: ReservationDetail = {
      reservationId: reservation.reservationId,
      reservationNo: reservation.reservationNo,
      scheduleId: reservation.scheduleId,
      status: 'CONFIRMED',
      holdExpiresAt: toKstIso(reservation.holdExpiresAtMillis),
      totalAmount: reservation.totalAmount,
      seats: mockDb.seats
        .filter((seat) => reservation.seatIds.includes(seat.seatId))
        .map(({ seatId, section, row, number, grade, price }) => ({
          seatId,
          section,
          row,
          number,
          grade,
          price,
        })),
      paymentToken: null,
      tickets: reservation.seatIds.map((seatId) => ({
        ticketNo: `T${reservation.reservationId}-${seatId}`,
        seatId,
      })),
    }
    renderReservation(reservation.reservationId, { variant: 'complete', primed })

    expect(
      await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('결제가 아직 확정되지 않았어요')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(reads).toHaveLength(0)
    expect(screen.getByRole('heading', { level: 1, name: '예매가 확정됐어요' })).toBeInTheDocument()
  })
})

describe('ReservationPage 상세(detail) 확정', () => {
  it('머리에 제목·상태 배지·일시·예매 번호를 두고 티켓 카드에는 배지를 두지 않는다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId, { heading: HEADING })

    expect(
      await screen.findByRole('heading', { level: 1, name: HEADING.title }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        `${formatDateTime(HEADING.startsAt)} · ${HEADING.venueName} · 예매 번호 ${reservation.reservationNo}`,
      ),
    ).toBeInTheDocument()
    expect(screen.getAllByText('예매 확정')).toHaveLength(1)
    const tickets = screen.getByRole('region', { name: '티켓 2매' })
    expect(within(tickets).queryByText('예매 확정')).not.toBeInTheDocument()
    expect(within(tickets).getAllByText(/티켓 번호/)).toHaveLength(2)
    expect(linkNamed('내 예매 목록')).toHaveAttribute('href', '/me/reservations')
    expect(screen.queryByRole('link', { name: '다른 공연 보기' })).not.toBeInTheDocument()
    expect(screen.queryByText('예매가 확정됐어요')).not.toBeInTheDocument()
  })

  it('공연 값이 없으면 "예매 {예매 번호}"가 제목이다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId)

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: `예매 ${reservation.reservationNo}`,
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(`예매 번호 ${reservation.reservationNo}`)).toBeInTheDocument()
    expect(screen.queryByText(HEADING.title)).not.toBeInTheDocument()
  })

  it('예매 단계 표시가 없다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    renderReservation(reservation.reservationId)

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('list', { name: '예매 단계' })).not.toBeInTheDocument()
  })
})

describe('ReservationPage 결제 대기(HELD)', () => {
  it('결제 토큰이 있으면 결제 가능 시각을 알리고 결제 화면으로 가는 버튼을 둔다', async () => {
    // 목은 응답 시각의 밀리초를 버린다. 초 단위 값으로 맞춰 기대 시각을 정한다
    const holdExpiresAtMillis = Math.floor((Date.now() + 5 * 60_000) / 1000) * 1000
    const reservation = seedReservation({ holdExpiresAtMillis })
    const { router } = renderReservation(reservation.reservationId, { heading: HEADING })

    expect(await screen.findByText('결제가 아직 확정되지 않았어요')).toBeInTheDocument()
    const payableUntil = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    }).format(new Date(holdExpiresAtMillis - 10_000))
    expect(
      screen.getByText(
        `${payableUntil}까지 확정되지 않으면 좌석이 풀려요. 이미 결제했다면 확정까지 잠시 걸릴 수 있어요.`,
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText('결제를 확인하고 있어요')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: HEADING.title })).toBeInTheDocument()
    expect(screen.getByText('결제 대기')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '잡아 둔 좌석' })).toBeInTheDocument()

    const pay = linkNamed('결제하기')
    expect(pay).toHaveAttribute('href', `/reservations/${reservation.reservationId}/payment`)
    await userEvent.click(pay)

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        `/reservations/${reservation.reservationId}/payment`,
      ),
    )
    // 결제 화면이 예매 조회로 결제 대상을 만들고, 결과 화면이 쓸 공연 제목 줄만 넘긴다
    expect(readReservationHeading(router.state.location.state)).toEqual(HEADING)
  })

  it('결제 토큰이 없으면(선점 만료 10초 안) 결제 버튼 없이 곧 풀린다고 알린다', async () => {
    const reservation = seedReservation({ holdExpiresAtMillis: Date.now() + 5_000 })
    renderReservation(reservation.reservationId)

    expect(await screen.findByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
    expect(screen.getByText('잡아 둔 좌석은 곧 풀려요.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '결제하기' })).not.toBeInTheDocument()
    expect(screen.queryByText('결제가 아직 확정되지 않았어요')).not.toBeInTheDocument()
    expect(linkNamed('내 예매 목록')).toBeInTheDocument()
  })

  it('결과 경로(complete)로 열어도 확정이 아니면 같은 결제 대기 화면이다', async () => {
    const reservation = seedReservation()
    renderReservation(reservation.reservationId, { variant: 'complete' })

    expect(await screen.findByText('결제가 아직 확정되지 않았어요')).toBeInTheDocument()
    expect(screen.queryByText('예매가 확정됐어요')).not.toBeInTheDocument()
  })

  it('이 브라우저에 결제 시도가 있으면 확인 중이라고 알리고 [결제 결과 확인]을 둔다', async () => {
    const reservation = seedReservation()
    savePaymentAttempt(reservation.reservationId, { token: 'stored-token', key: 'stored-key' })
    renderReservation(reservation.reservationId, { heading: HEADING })

    expect(await screen.findByText('결제를 확인하고 있어요')).toBeInTheDocument()
    expect(screen.getByText('확정되면 이 화면이 바뀌어요.')).toBeInTheDocument()
    expect(screen.queryByText('결제가 아직 확정되지 않았어요')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '결제하기' })).not.toBeInTheDocument()
    expect(linkNamed('결제 결과 확인')).toHaveAttribute(
      'href',
      `/reservations/${reservation.reservationId}/payment`,
    )
  })

  it('결제 시도는 예매마다 따로라 다른 예매의 시도는 보지 않는다', async () => {
    const reservation = seedReservation()
    savePaymentAttempt(reservation.reservationId + 1, { token: 'other-token', key: 'other-key' })
    renderReservation(reservation.reservationId)

    expect(await screen.findByText('결제가 아직 확정되지 않았어요')).toBeInTheDocument()
    expect(screen.queryByText('결제를 확인하고 있어요')).not.toBeInTheDocument()
  })

  it('[결제 결과 확인]을 누르면 결제 화면으로 가며 공연 제목 줄을 넘긴다', async () => {
    const reservation = seedReservation()
    savePaymentAttempt(reservation.reservationId, { token: 'stored-token', key: 'stored-key' })
    const { router } = renderReservation(reservation.reservationId, { heading: HEADING })

    await userEvent.click(await screen.findByRole('link', { name: '결제 결과 확인' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        `/reservations/${reservation.reservationId}/payment`,
      ),
    )
    expect(readReservationHeading(router.state.location.state)).toEqual(HEADING)
  })

  it('공연 값이 없으면 결제 화면으로 빈 이동 값을 넘긴다', async () => {
    const reservation = seedReservation()
    const { router } = renderReservation(reservation.reservationId)

    await userEvent.click(await screen.findByRole('link', { name: '결제하기' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(
        `/reservations/${reservation.reservationId}/payment`,
      ),
    )
    expect(readReservationHeading(router.state.location.state)).toBeNull()
  })
})

describe('ReservationPage 결제 대기 동안 다시 받기', () => {
  it('5초마다 다시 받다가 확정이 되면 화면이 확정으로 바뀐다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    // 첫 조회는 HELD, 다음 조회부터 CONFIRMED
    const reservation = seedReservation({ confirmAfterChecks: 1 })
    const reads = recordRequests(`/api/v1/reservations/${reservation.reservationId}`)
    renderReservation(reservation.reservationId, { variant: 'complete' })

    expect(await screen.findByText('결제가 아직 확정되지 않았어요')).toBeInTheDocument()
    expect(reads).toHaveLength(1)

    await advance(4000)
    expect(reads).toHaveLength(1)
    await advance(2000)
    expect(
      await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' }),
    ).toBeInTheDocument()
    expect(reads).toHaveLength(2)
    expect(screen.queryByText('결제가 아직 확정되지 않았어요')).not.toBeInTheDocument()

    // 확정되면 더 받지 않는다
    await advance(20_000)
    expect(reads).toHaveLength(2)
  })

  it('선점 만료 시각 + 30초가 지나면 다시 받기를 멈춘다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    // 만료 시각이 20초 전이다. 만료 스케줄러가 아직 EXPIRED로 바꾸지 못한 것처럼 확정 대기로 HELD를 붙든다.
    // 목은 응답 시각의 밀리초를 버리므로 초 단위로 맞춘다
    const reservation = seedReservation({
      holdExpiresAtMillis: Math.floor(Date.now() / 1000) * 1000 - 20_000,
      confirmAfterChecks: 1000,
    })
    const reads = recordRequests(`/api/v1/reservations/${reservation.reservationId}`)
    renderReservation(reservation.reservationId)

    expect(await screen.findByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
    expect(reads).toHaveLength(1)

    // 5초 뒤(만료 + 25초)는 아직 더 받고, 10초 뒤(만료 + 30초)에 받은 결과로 멈춘다
    await advance(5000)
    await waitFor(() => expect(reads).toHaveLength(2))
    await advance(5000)
    await waitFor(() => expect(reads).toHaveLength(3))

    await advance(60_000)
    expect(reads).toHaveLength(3)
    expect(screen.getByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
  })

  it('확정 예매는 60초 동안 다시 받지 않고 그 뒤에는 다시 받는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const reservation = seedReservation({ status: 'CONFIRMED' })
    const reads = recordRequests(`/api/v1/reservations/${reservation.reservationId}`)
    renderReservation(reservation.reservationId)

    await screen.findByRole('heading', { level: 1, name: `예매 ${reservation.reservationNo}` })
    expect(reads).toHaveLength(1)

    await advance(30_000, 10_000)
    returnToWindow()
    await advance(1000)
    expect(reads).toHaveLength(1)

    await advance(31_000, 10_000)
    returnToWindow()
    await waitFor(() => expect(reads).toHaveLength(2))
  })

  it('HELD는 창이 다시 보이면 바로 다시 받는다(신선하지 않다)', async () => {
    const reservation = seedReservation()
    const reads = recordRequests(`/api/v1/reservations/${reservation.reservationId}`)
    renderReservation(reservation.reservationId)

    await screen.findByText('결제가 아직 확정되지 않았어요')
    returnToWindow()

    await waitFor(() => expect(reads).toHaveLength(2))
  })
})

const ENDED: { status: ReservationStatus; title: string; badge: string; description: string }[] = [
  {
    status: 'EXPIRED',
    title: '예매가 확정되지 않았어요',
    badge: '기한 만료',
    description: '결제 가능 시간이 지나 잡아 둔 좌석이 풀렸어요. 결제한 금액이 있으면 환불돼요.',
  },
  {
    status: 'RELEASED',
    title: '좌석이 풀렸어요',
    badge: '좌석 반환',
    description: '결제가 거절돼 잡아 둔 좌석을 돌려놓았어요.',
  },
  {
    status: 'CANCELED',
    title: '취소된 예매예요',
    badge: '취소됨',
    description: '예매를 취소해 좌석을 돌려놓았어요.',
  },
]

describe.each(ENDED)('ReservationPage $status', ({ status, title, badge, description }) => {
  it.each(['detail', 'complete'] as const)(
    '%s 경로에서 상태별 결과 머리와 풀린 좌석 카드를 보인다',
    async (variant) => {
      const reservation = seedReservation({ status })
      renderReservation(reservation.reservationId, { variant })

      expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
      expect(screen.getByText(description)).toBeInTheDocument()
      const seats = screen.getByRole('region', { name: '풀린 좌석' })
      expect(within(seats).getByText(badge)).toBeInTheDocument()
      expect(within(seats).getAllByRole('listitem')).toHaveLength(2)
      expect(within(seats).getByText(reservation.reservationNo)).toBeInTheDocument()
      expect(within(seats).getByText('330,000원')).toBeInTheDocument()
      expect(linkNamed('내 예매 목록')).toHaveAttribute('href', '/me/reservations')
      expect(linkNamed('공연 보러 가기')).toHaveAttribute('href', '/')
      expect(screen.queryByRole('link', { name: '결제하기' })).not.toBeInTheDocument()
      expect(screen.queryByText(/티켓 번호/)).not.toBeInTheDocument()
    },
  )

  it('공연 값이 있으면 설명 앞에 공연 한 줄을 붙인다', async () => {
    const reservation = seedReservation({ status })
    renderReservation(reservation.reservationId, { heading: HEADING })

    await screen.findByRole('heading', { level: 1, name: title })
    expect(screen.getByText(`${HEADING_LINE} · ${description}`)).toBeInTheDocument()
  })
})

describe('ReservationPage 조회 실패', () => {
  it('없는 예매는 "찾는 예매가 없어요"와 내 예매 보기를 보인다', async () => {
    renderReservation(987654)

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(linkNamed('내 예매 보기')).toHaveAttribute('href', '/me/reservations')
  })

  it('남의 예매도 없는 예매와 같은 안내다', async () => {
    const reservation = seedReservation({ userId: 1 })
    renderReservation(reservation.reservationId)

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(screen.queryByText(reservation.reservationNo)).not.toBeInTheDocument()
  })

  it('주소의 예매 번호가 숫자가 아니면 조회 없이 같은 안내다', async () => {
    const reads = recordRequests('/api/v1/reservations/abc')
    renderReservation('abc')

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(reads).toHaveLength(0)
  })

  it('서버 오류는 오류 안내와 다시 시도를 보이고, 다시 시도하면 예매를 보인다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    let calls = 0
    mockServer.use(
      http.get(`/api/v1/reservations/${reservation.reservationId}`, () => {
        calls += 1
        return calls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    renderReservation(reservation.reservationId)

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.queryByText(/찾는 예매가 없어요/)).not.toBeInTheDocument()
    await userEvent.click(retry)

    expect(
      await screen.findByRole('heading', { level: 1, name: `예매 ${reservation.reservationNo}` }),
    ).toBeInTheDocument()
    expect(calls).toBe(2)
  })

  it('받은 뒤 다시 받기가 실패해도 예매는 그대로 두고 위쪽에 오류 안내를 보이며, 다시 시도하면 안내가 사라진다', async () => {
    const reservation = seedReservation()
    respondFromSecondRead(reservation.reservationId, (call) =>
      call === 2 ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다') : undefined,
    )
    renderReservation(reservation.reservationId, { heading: HEADING })
    const title = await screen.findByRole('heading', { level: 1, name: HEADING.title })

    returnToWindow()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    // 내용은 그대로이고 안내는 내용 위에 있다
    expect(screen.getByRole('heading', { level: 1, name: HEADING.title })).toBe(title)
    expect(screen.getByText('결제가 아직 확정되지 않았어요')).toBeInTheDocument()
    expect(screen.queryByText(/찾는 예매가 없어요/)).not.toBeInTheDocument()
    expect(retry.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await userEvent.click(retry)

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('heading', { level: 1, name: HEADING.title })).toBeInTheDocument()
  })

  it('받은 뒤 다시 받기가 404면 찾는 예매가 없다고 알린다', async () => {
    const reservation = seedReservation()
    respondFromSecondRead(reservation.reservationId, () =>
      fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다'),
    )
    renderReservation(reservation.reservationId, { heading: HEADING })
    await screen.findByRole('heading', { level: 1, name: HEADING.title })

    returnToWindow()

    expect(await screen.findByText(/찾는 예매가 없어요/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: HEADING.title })).not.toBeInTheDocument()
    expect(linkNamed('내 예매 보기')).toHaveAttribute('href', '/me/reservations')
  })

  it('확정 예매도 다시 받기가 실패하면 확정을 그대로 두고 오류 안내를 위에 보인다', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    respondFromSecondRead(reservation.reservationId, () =>
      fail(429, 'TOO_MANY_REQUESTS', '요청이 너무 많습니다'),
    )
    // 확정은 60초 동안 신선해 창 복귀로는 다시 받지 않는다. 목록 캐시를 낡게 만들어 직접 다시 받는다
    const { queryClient } = renderReservation(reservation.reservationId, {
      variant: 'complete',
    })
    await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' })

    await act(() =>
      queryClient.refetchQueries({
        queryKey: reservationQueryKeys.detail(reservation.reservationId),
      }),
    )

    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '예매가 확정됐어요' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '티켓 2매' })).toBeInTheDocument()
  })
})
