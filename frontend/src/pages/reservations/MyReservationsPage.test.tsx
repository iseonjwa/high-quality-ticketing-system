import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { mockDb, toKstIso, type MockReservation } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { formatDateTime, formatWon } from '@/lib/format'
import { readReservationHeading } from '@/pages/reservations/reservationNavigation'
import { ON_SALE_SCHEDULE_ID, recordRequests, signInAsDemo } from '@/test/api'
import { seedReservation } from '@/test/reservations'

const CONCERT_TITLE = '노을섬 단독 콘서트 〈새벽의 좌석〉'
const MINE_API = '/api/v1/reservations/me'

beforeEach(async () => {
  await signInAsDemo()
})

// 조회 재시도를 끈 클라이언트로 그려 실패 화면을 바로 본다
function renderMine() {
  const router = createMemoryRouter(routes, { initialEntries: ['/me/reservations'] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

function scheduleStartsAt() {
  const schedule = mockDb.schedules.find((item) => item.scheduleId === ON_SALE_SCHEDULE_ID)!
  return toKstIso(schedule.startsAtMillis)
}

// 목록의 예매 항목 링크(누르면 상세로 간다)
const itemLinks = () => within(screen.getByRole('list')).getAllByRole('link')

function seedMany(count: number) {
  return Array.from({ length: count }, () => seedReservation({ status: 'CONFIRMED' }))
}

describe('MyReservationsPage', () => {
  it('예매가 없으면 빈 안내와 공연 보러 가기를 보인다', async () => {
    renderMine()

    expect(await screen.findByText('아직 예매한 공연이 없어요')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '내 예매' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공연 보러 가기' })).toHaveAttribute('href', '/')
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('항목마다 공연 제목·상태 배지·일시·예매 번호·합계를 최신순으로 보인다', async () => {
    const older = seedReservation({ status: 'CONFIRMED', totalAmount: 110000 })
    const newer = seedReservation({ status: 'HELD', totalAmount: 330000 })
    renderMine()

    await screen.findByRole('list')
    const links = itemLinks()
    expect(links).toHaveLength(2)
    expect(links[0]).toHaveAttribute('href', `/reservations/${newer.reservationId}`)
    expect(links[1]).toHaveAttribute('href', `/reservations/${older.reservationId}`)
    expect(links[0]).toHaveTextContent(CONCERT_TITLE)
    expect(links[0]).toHaveTextContent('결제 대기')
    expect(links[0]).toHaveTextContent(
      `${formatDateTime(scheduleStartsAt())} · ${newer.reservationNo}`,
    )
    expect(links[0]).toHaveTextContent(formatWon(330000))
    expect(links[1]).toHaveTextContent('예매 확정')
    expect(links[1]).toHaveTextContent(formatWon(110000))
  })

  it('남의 예매는 보이지 않는다', async () => {
    seedReservation({ userId: 1, status: 'CONFIRMED' })
    const mine = seedReservation({ status: 'CONFIRMED' })
    renderMine()

    await screen.findByRole('list')
    expect(itemLinks()).toHaveLength(1)
    expect(screen.getByText(new RegExp(mine.reservationNo))).toBeInTheDocument()
  })

  it('다섯 가지 예매 상태를 각자의 배지 글자로 보인다', async () => {
    const statuses: [MockReservation['status'], string][] = [
      ['HELD', '결제 대기'],
      ['CONFIRMED', '예매 확정'],
      ['EXPIRED', '기한 만료'],
      ['RELEASED', '좌석 반환'],
      ['CANCELED', '취소됨'],
    ]
    for (const [status] of statuses) {
      seedReservation({ status })
    }
    renderMine()

    await screen.findByRole('list')
    const links = itemLinks()
    expect(links).toHaveLength(5)
    // 최신순이라 마지막에 만든 상태가 먼저다
    ;[...statuses].reverse().forEach(([, label], index) => {
      expect(links[index]).toHaveTextContent(label)
    })
  })

  it('항목을 누르면 상세로 가고 공연 제목·시작 시각을 넘긴다(공연장은 없다)', async () => {
    const reservation = seedReservation({ status: 'CONFIRMED' })
    const { router } = renderMine()

    await userEvent.click(await screen.findByRole('link', { name: new RegExp(CONCERT_TITLE) }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe(`/reservations/${reservation.reservationId}`),
    )
    expect(readReservationHeading(router.state.location.state)).toEqual({
      title: CONCERT_TITLE,
      startsAt: scheduleStartsAt(),
      venueName: null,
    })
    expect(
      await screen.findByRole('heading', { level: 1, name: CONCERT_TITLE }),
    ).toBeInTheDocument()
  })
})

describe('MyReservationsPage 더 보기', () => {
  it('20건씩 받고 더 보기로 다음 쪽(page=1)을 기존 항목 뒤에 붙이며, 다 받으면 더 보기가 사라진다', async () => {
    seedMany(25)
    const requests = recordRequests(MINE_API)
    renderMine()

    await screen.findByRole('list')
    expect(itemLinks()).toHaveLength(20)
    expect(requests).toHaveLength(1)
    const first = new URL(requests[0].url).searchParams
    expect([first.get('page'), first.get('size')]).toEqual(['0', '20'])
    const firstTexts = itemLinks().map((link) => link.textContent)

    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => expect(itemLinks()).toHaveLength(25))
    expect(requests).toHaveLength(2)
    const second = new URL(requests[1].url).searchParams
    expect([second.get('page'), second.get('size')]).toEqual(['1', '20'])
    // 기존 항목은 그대로 앞에 남는다
    expect(
      itemLinks()
        .slice(0, 20)
        .map((link) => link.textContent),
    ).toEqual(firstTexts)
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('정확히 20건이면 더 보기를 보이지 않는다', async () => {
    seedMany(20)
    renderMine()

    await screen.findByRole('list')
    expect(itemLinks()).toHaveLength(20)
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('받은 수가 totalElements보다 적을 때만 더 보기를 보인다(21건이면 보인다)', async () => {
    seedMany(21)
    renderMine()

    await screen.findByRole('list')
    expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument()
  })
})

describe('MyReservationsPage 조회 실패', () => {
  it('오류 안내와 다시 시도를 보이고, 다시 시도하면 목록을 보인다', async () => {
    seedReservation({ status: 'CONFIRMED' })
    let calls = 0
    mockServer.use(
      http.get(MINE_API, () => {
        calls += 1
        return calls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    renderMine()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.queryByText('아직 예매한 공연이 없어요')).not.toBeInTheDocument()
    await userEvent.click(retry)

    await screen.findByRole('list')
    expect(itemLinks()).toHaveLength(1)
    expect(calls).toBe(2)
  })
})
