import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { reservationQueryKeys } from '@/api/reservations'
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
const itemList = () => within(screen.getByRole('main')).getByRole('list')
const itemLinks = () => within(itemList()).getAllByRole('link')

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

    await within(await screen.findByRole('main')).findByRole('list')
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

    await within(await screen.findByRole('main')).findByRole('list')
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

    await within(await screen.findByRole('main')).findByRole('list')
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

    await within(await screen.findByRole('main')).findByRole('list')
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

    await within(await screen.findByRole('main')).findByRole('list')
    expect(itemLinks()).toHaveLength(20)
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('받은 수가 totalElements보다 적을 때만 더 보기를 보인다(21건이면 보인다)', async () => {
    seedMany(21)
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument()
  })

  it('더 보기가 실패해도 받은 목록은 그대로 두고 더 보기 위에 오류 안내를 보이며, 다시 시도하면 다음 쪽을 받는다', async () => {
    seedMany(25)
    let nextPageCalls = 0
    mockServer.use(
      http.get(MINE_API, ({ request }) => {
        if (new URL(request.url).searchParams.get('page') !== '1') {
          return undefined
        }
        nextPageCalls += 1
        return nextPageCalls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    const requests = recordRequests(MINE_API)
    renderMine()
    await within(await screen.findByRole('main')).findByRole('list')
    const firstTexts = itemLinks().map((link) => link.textContent)

    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(itemLinks()).toHaveLength(20)
    expect(itemLinks().map((link) => link.textContent)).toEqual(firstTexts)
    // 오류 안내는 목록 아래, 더 보기 버튼 위에 있다
    const more = screen.getByRole('button', { name: '더 보기' })
    expect(retry.compareDocumentPosition(more) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(
      itemList().compareDocumentPosition(retry) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(screen.queryByText('아직 예매한 공연이 없어요')).not.toBeInTheDocument()

    await userEvent.click(retry)

    await waitFor(() => expect(itemLinks()).toHaveLength(25))
    const pages = requests.map((request) => new URL(request.url).searchParams.get('page'))
    expect(pages).toEqual(['0', '1', '1'])
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('쪽 사이에 새 예매가 생겨 앞 쪽의 마지막 항목이 다음 쪽에 또 와도 한 번만 보인다', async () => {
    seedMany(21)
    renderMine()
    await within(await screen.findByRole('main')).findByRole('list')
    expect(itemLinks()).toHaveLength(20)

    // 새 예매가 맨 앞에 끼어 모든 항목이 한 칸씩 밀린다. 다음 쪽(offset 20)은 이미 받은 마지막 항목부터 시작한다
    seedReservation({ status: 'CONFIRMED' })
    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => expect(itemLinks()).toHaveLength(21))
    const hrefs = itemLinks().map((link) => link.getAttribute('href'))
    expect(new Set(hrefs).size).toBe(hrefs.length)
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('더 보기로 붙인 첫 항목으로 초점을 옮기고 몇 건 더 불러왔는지 알린다', async () => {
    seedMany(25)
    renderMine()
    await within(await screen.findByRole('main')).findByRole('list')
    expect(screen.getByRole('status')).toBeEmptyDOMElement()

    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => expect(itemLinks()).toHaveLength(25))
    await waitFor(() => expect(itemLinks()[20]).toHaveFocus())
    expect(screen.getByRole('status')).toHaveTextContent('5건 더 불러왔어요')
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

    await within(await screen.findByRole('main')).findByRole('list')
    expect(itemLinks()).toHaveLength(1)
    expect(calls).toBe(2)
  })
})

describe('MyReservationsPage 다시 받기 실패', () => {
  it('받은 목록이 있으면 다시 받기가 실패해도 목록을 그대로 두고 오류 안내를 보이며, 다시 시도하면 안내가 사라진다', async () => {
    seedMany(2)
    const { queryClient } = renderMine()
    await within(await screen.findByRole('main')).findByRole('list')
    const texts = itemLinks().map((link) => link.textContent)
    let calls = 0
    mockServer.use(
      http.get(MINE_API, () => {
        calls += 1
        return calls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )

    await act(() => queryClient.refetchQueries({ queryKey: reservationQueryKeys.mine }))

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(itemLinks().map((link) => link.textContent)).toEqual(texts)
    expect(screen.queryByText('아직 예매한 공연이 없어요')).not.toBeInTheDocument()

    await userEvent.click(retry)

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument(),
    )
    expect(itemLinks()).toHaveLength(2)
    expect(calls).toBe(2)
  })
})
