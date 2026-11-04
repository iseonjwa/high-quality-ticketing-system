import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  reservationQueryKeys,
  type ReservationDetail,
  type ReservationStatus,
} from '@/api/reservations'
import { routes } from '@/app/routes'
import { mockDb, toKstIso } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { formatDateTime } from '@/lib/format'
import { reservationNavigationState } from '@/pages/reservations/reservationNavigation'
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

  it('결제 화면이 넣은 확정 예매가 캐시에 있으면 조회 응답을 기다리지 않고 확정을 보인다', async () => {
    // 서버(목)는 아직 HELD로 답하고 응답도 붙잡아 둔다. 보이는 확정은 캐시에서 온 것이다
    const reservation = seedReservation({ status: 'HELD' })
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    mockServer.use(
      http.get(`/api/v1/reservations/${reservation.reservationId}`, async () => {
        await gate
        return undefined
      }),
    )
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
    try {
      renderReservation(reservation.reservationId, { variant: 'complete', primed })

      expect(
        await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' }),
      ).toBeInTheDocument()
      expect(screen.queryByText('아직 결제하지 않았어요')).not.toBeInTheDocument()
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
      await waitFor(() => expect(reads).toHaveLength(1))
    } finally {
      release()
    }
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

    expect(await screen.findByText('아직 결제하지 않았어요')).toBeInTheDocument()
    const payableUntil = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    }).format(new Date(holdExpiresAtMillis - 10_000))
    expect(
      screen.getByText(`${payableUntil}까지 결제하지 않으면 좌석이 풀려요.`),
    ).toBeInTheDocument()
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
    // 결제 화면이 예매 조회로 결제 대상을 만들도록 이동 값을 넘기지 않는다
    expect(router.state.location.state).toBeNull()
  })

  it('결제 토큰이 없으면(선점 만료 10초 안) 결제 버튼 없이 곧 풀린다고 알린다', async () => {
    const reservation = seedReservation({ holdExpiresAtMillis: Date.now() + 5_000 })
    renderReservation(reservation.reservationId)

    expect(await screen.findByText('결제 가능 시간이 지났어요')).toBeInTheDocument()
    expect(screen.getByText('잡아 둔 좌석은 곧 풀려요.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '결제하기' })).not.toBeInTheDocument()
    expect(screen.queryByText('아직 결제하지 않았어요')).not.toBeInTheDocument()
    expect(linkNamed('내 예매 목록')).toBeInTheDocument()
  })

  it('결과 경로(complete)로 열어도 확정이 아니면 같은 결제 대기 화면이다', async () => {
    const reservation = seedReservation()
    renderReservation(reservation.reservationId, { variant: 'complete' })

    expect(await screen.findByText('아직 결제하지 않았어요')).toBeInTheDocument()
    expect(screen.queryByText('예매가 확정됐어요')).not.toBeInTheDocument()
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
})
