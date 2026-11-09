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
import { fail, ok } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { formatWon, kstParts } from '@/lib/format'
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

// 날짜 칸(티켓 왼쪽)과 공연 제목 글자
const stubOf = (link: HTMLElement) => link.querySelector('[data-slot="ticket-stub"]')!
const titleOf = (link: HTMLElement) => within(link).getByText(CONCERT_TITLE, { selector: 'span' })

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
    // 날짜 칸(연.월, 일, 요일 시:분)과 예매 번호를 따로 보인다
    const date = kstParts(scheduleStartsAt())
    expect(links[0]).toHaveTextContent(`${date.year}.${date.month}`)
    expect(links[0]).toHaveTextContent(`${date.weekday} ${date.hour}:${date.minute}`)
    expect(links[0]).toHaveTextContent(`예매 번호 ${newer.reservationNo}`)
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

  // 티켓 왼쪽 날짜 칸. 색만으로 구분하지 않도록 오른쪽에 배지가 함께 있지만, 칸 색 자체도 상태를 따른다
  it.each([
    { status: 'CONFIRMED', tone: 'bg-primary-600' },
    { status: 'HELD', tone: 'bg-warning-50' },
    { status: 'EXPIRED', tone: 'bg-gray-100' },
    { status: 'RELEASED', tone: 'bg-gray-100' },
    { status: 'CANCELED', tone: 'bg-gray-100' },
  ] as const)('$status 예매의 날짜 칸은 $tone 색이다', async ({ status, tone }) => {
    seedReservation({ status })
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    expect(stubOf(itemLinks()[0]).className).toContain(tone)
  })

  it('확정·결제 대기·끝난 예매의 날짜 칸 색이 서로 다르다', async () => {
    seedReservation({ status: 'EXPIRED' })
    seedReservation({ status: 'HELD' })
    seedReservation({ status: 'CONFIRMED' })
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    const tones = itemLinks().map((link) => stubOf(link).className)
    expect(new Set(tones).size).toBe(3)
  })

  it('날짜 칸은 공연 시작 시각을 한국 시각의 연.월, 일, 요일 시:분으로 보인다', async () => {
    mockServer.use(
      http.get(MINE_API, () =>
        ok({
          content: [
            {
              reservationId: 901,
              reservationNo: 'R20261102-000901',
              concertTitle: CONCERT_TITLE,
              // 한국 시각 2026-11-21 00:30 — UTC 날짜(20일)와 한국 날짜(21일)가 다른 시각이다
              startsAt: '2026-11-20T15:30:00Z',
              status: 'CONFIRMED',
              totalAmount: 110000,
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
        }),
      ),
    )
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    const stub = stubOf(itemLinks()[0])
    expect(Array.from(stub.children).map((child) => child.textContent)).toEqual([
      '2026.11',
      '21',
      '토 00:30',
    ])
  })

  it('링크 이름은 공연 제목·일시·상태·예매 번호·금액 순서이고, 숨긴 날짜 칸 글자는 넣지 않는다', async () => {
    seedReservation({ status: 'CONFIRMED' })
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    const [link] = itemLinks()
    // Chrome은 링크 안 <article> 글을 이름에서 빼서 이름이 빈 링크가 됐다(셀프 리뷰). 링크 안에 article을 두지 않는다
    expect(link.querySelector('article')).toBeNull()
    expect(stubOf(link)).toHaveAttribute('aria-hidden', 'true')
    expect(link).toHaveAccessibleName(
      new RegExp(
        `^${CONCERT_TITLE}, \\d{4}\\.\\d{2}\\.\\d{2} \\(.\\) \\d{2}:\\d{2}, 예매 확정, 예매 번호 \\S+, [\\d,]+원$`,
      ),
    )
  })

  it('날짜 칸의 작은 글자는 바탕마다 4.5:1을 넘는 색이다(흐리게 하지 않는다)', async () => {
    for (const status of ['CONFIRMED', 'HELD', 'EXPIRED'] as const) {
      seedReservation({ status })
    }
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    const captions = itemLinks().map((link) => stubOf(link).children[0].className)
    expect(captions.every((name) => !name.includes('opacity'))).toBe(true)
    // 최신순: 만료, 결제 대기, 확정
    expect(captions[0]).toContain('text-gray-600')
    expect(captions[1]).toContain('text-warning-700')
    // 확정 칸은 primary-600 바탕이라 글자도 모드에 따라 함께 뒤집히는 gray-0이다(다크에서 바탕이 밝아진다)
    expect(captions[2]).toContain('text-gray-0/85')
  })

  it('끝난 예매(만료·반환·취소)의 제목은 흐린 색이고 확정·결제 대기는 진한 색이다', async () => {
    const statuses = ['CONFIRMED', 'HELD', 'EXPIRED', 'RELEASED', 'CANCELED'] as const
    for (const status of statuses) {
      seedReservation({ status })
    }
    renderMine()

    await within(await screen.findByRole('main')).findByRole('list')
    // 최신순이라 마지막에 만든 상태가 먼저다
    const titleClasses = itemLinks().map((link) => titleOf(link).className)
    expect(titleClasses.map((name) => (name.includes('text-gray-500') ? 'dim' : 'dark'))).toEqual([
      'dim',
      'dim',
      'dim',
      'dark',
      'dark',
    ])
    expect(titleClasses[3]).toContain('text-gray-900')
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
