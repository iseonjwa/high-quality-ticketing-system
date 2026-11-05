import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/routes'
import { fail, ok } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests } from '@/test/api'
import { renderRoutes } from '@/test/render'

const SECOND = 1000
const HOUR = 60 * 60 * SECOND
const DAY = 24 * HOUR

const iso = (millis: number) => new Date(millis).toISOString()

// 실제 응답(ISE-99)과 같은 모양
function detail(serverNow: number, schedules: Record<string, unknown>[]) {
  return {
    concertId: 7,
    title: '시계 확인 공연',
    // 설명 없이 등록한 공연은 null로 온다
    description: null,
    serverTime: iso(serverNow),
    schedules: schedules.map((schedule, index) => ({
      scheduleId: 32 + index,
      venueName: '한강 아트홀',
      startsAt: iso(serverNow + DAY),
      openAt: iso(serverNow - SECOND),
      maxPerUser: 4,
      saleStatus: 'ON_SALE',
      ...schedule,
    })),
  }
}

function serveDetail(body: unknown) {
  mockServer.use(http.get('/api/v1/concerts/:concertId', () => ok(body)))
}

// 가짜 시계를 250ms씩 나눠 돌린다. 카운트다운(250ms)과 화면 갱신(1초)이 따로 그려지는 순서를 실제처럼 만든다
async function tick(millis: number) {
  for (let elapsed = 0; elapsed < millis; elapsed += 250) {
    await act(async () => {
      vi.advanceTimersByTime(250)
    })
  }
}

// 오른쪽(또는 위쪽)의 예매 패널
async function bookingPanel() {
  return screen.findByRole('complementary', { name: '예매' })
}

const labelOf = (radio: HTMLElement) => radio.closest('label')!

describe('ConcertDetailPage', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  // 완료 기준: 브라우저 시계를 바꿔도 서버 시각 기준, 오픈 시각이 되면 새로고침 없이 [예매하기]가 켜진다
  it('브라우저 시계가 서버보다 1시간 늦어도 서버 시각으로 세고, 0이 되면 새로고침 없이 예매하기가 켜진다', async () => {
    // 시계와 주기 타이머만 가짜로 돌린다. 목 서버와 조회 재시도가 쓰는 setTimeout은 그대로 둔다
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    const serverNow = Date.now() + HOUR
    // 카운트다운(250ms)이 화면 갱신(1초)보다 먼저 0을 보는 오픈 시각이다. 둘이 같은 순간에 갱신되면 한 번에 그려져
    // 열린 순간을 알리는 문구를 확인할 수 없다
    serveDetail(detail(serverNow, [{ openAt: iso(serverNow + 2100), saleStatus: 'BEFORE_OPEN' }]))
    const detailRequests = recordRequests('/api/v1/concerts/7')
    renderRoutes(routes, '/concerts/7')

    const timer = await screen.findByRole('timer', { name: '오픈까지 남은 시간' })
    expect(timer).toHaveTextContent('00:00:03')
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()

    await tick(1000)
    expect(timer).toHaveTextContent('00:00:02')
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()

    await tick(1000)
    expect(timer).toHaveTextContent('00:00:01')
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()

    await tick(1000)
    expect(screen.getByRole('link', { name: '예매하기' })).toHaveAttribute(
      'href',
      '/schedules/32/queue',
    )
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
    // 다시 조회하지 않고 켜졌다
    expect(detailRequests).toHaveLength(1)
    // 카운트다운은 매초 읽지 않으므로 열린 순간을 따로 알린다
    expect(screen.getByText('예매가 열렸어요')).toHaveAttribute('aria-live', 'polite')
  })

  it('받은 뒤 다시 조회하다 실패해도 카운트다운과 예매하기를 오류 알림으로 바꾸지 않는다', async () => {
    const serverNow = Date.now()
    serveDetail(detail(serverNow, [{ openAt: iso(serverNow + HOUR), saleStatus: 'BEFORE_OPEN' }]))
    const { queryClient } = renderRoutes(routes, '/concerts/7')
    await screen.findByRole('timer', { name: '오픈까지 남은 시간' })

    mockServer.use(
      http.get('/api/v1/concerts/:concertId', () =>
        fail(429, 'RATE_LIMITED', '요청이 너무 많습니다'),
      ),
    )
    // Note: TanStack Query는 화면 갱신을 다음 매크로태스크로 미룬다. 기다리지 않고 검사하면 갱신 전 화면을 본다
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['concerts', 'detail', 7] })
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(queryClient.getQueryState(['concerts', 'detail', 7])?.status).toBe('error')

    expect(screen.getByRole('timer', { name: '오픈까지 남은 시간' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('판매 중인 회차를 고르면 예매하기 링크를, 서버가 판매 종료라고 한 회차를 고르면 안내만 보인다', async () => {
    const now = Date.now()
    serveDetail(detail(now, [{}, { saleStatus: 'CLOSED' }]))
    renderRoutes(routes, '/concerts/7')

    const panel = await bookingPanel()
    const [onSale, closed] = within(panel).getAllByRole('radio')
    expect(within(labelOf(onSale)).getByText('예매 중')).toBeInTheDocument()
    expect(onSale).toBeChecked()
    expect(within(panel).getByRole('link', { name: '예매하기' })).toHaveAttribute(
      'href',
      '/schedules/32/queue',
    )

    await userEvent.click(closed)

    // 시각으로는 판매 중이어도 서버가 CLOSED면 따른다
    expect(within(labelOf(closed)).getByText('판매 종료')).toBeInTheDocument()
    expect(within(panel).getByText('판매가 끝났어요')).toBeInTheDocument()
    expect(within(panel).queryByRole('link', { name: '예매하기' })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: '예매하기' })).not.toBeInTheDocument()
  })

  it('회차를 바꾸면 예매 패널이 고른 회차의 카운트다운·버튼·매수로 바뀐다', async () => {
    const now = Date.now()
    serveDetail(detail(now, [{ openAt: iso(now + HOUR), maxPerUser: 2 }, { maxPerUser: 6 }]))
    renderRoutes(routes, '/concerts/7')

    const panel = within(await bookingPanel())
    const [beforeOpen, onSale] = panel.getAllByRole('radio')
    // 예매 중인 회차가 있으면 그 회차를 먼저 고른 상태다
    expect(onSale).toBeChecked()
    expect(panel.getByRole('link', { name: '예매하기' })).toHaveAttribute(
      'href',
      '/schedules/33/queue',
    )
    expect(panel.getByText(/1인 최대 6매/)).toBeInTheDocument()
    expect(panel.queryByRole('timer')).not.toBeInTheDocument()

    await userEvent.click(beforeOpen)

    expect(beforeOpen).toBeChecked()
    expect(onSale).not.toBeChecked()
    expect(panel.getByRole('timer', { name: '오픈까지 남은 시간' })).toBeInTheDocument()
    expect(panel.getByRole('button', { name: '예매하기' })).toBeDisabled()
    expect(panel.queryByRole('link', { name: '예매하기' })).not.toBeInTheDocument()
    expect(panel.getByText(/1인 최대 2매/)).toBeInTheDocument()
    expect(panel.getByText(/오픈$/)).toBeInTheDocument()

    await userEvent.click(onSale)

    expect(panel.getByRole('link', { name: '예매하기' })).toHaveAttribute(
      'href',
      '/schedules/33/queue',
    )
    expect(panel.queryByRole('timer')).not.toBeInTheDocument()
  })

  // 고르기 전 기본 선택: 예매 중 첫 회차 → 오픈 예정 첫 회차 → 첫 회차
  it.each([
    {
      name: '예매 중인 회차가 있으면 판매 종료·오픈 예정보다 그 첫 회차',
      schedules: [{ saleStatus: 'CLOSED' }, { openAt: iso(Date.now() + HOUR) }, {}, {}],
      checked: 2,
    },
    {
      name: '예매 중이 없으면 오픈 예정인 첫 회차',
      schedules: [
        { saleStatus: 'CLOSED' },
        { openAt: iso(Date.now() + 2 * HOUR) },
        { openAt: iso(Date.now() + HOUR) },
      ],
      checked: 1,
    },
    {
      name: '모두 판매 종료면 첫 회차',
      schedules: [{ saleStatus: 'CLOSED' }, { saleStatus: 'CLOSED' }],
      checked: 0,
    },
  ])('기본 선택: $name', async ({ schedules, checked }) => {
    serveDetail(detail(Date.now(), schedules))
    renderRoutes(routes, '/concerts/7')

    const radios = within(await bookingPanel()).getAllByRole('radio')

    expect(radios).toHaveLength(schedules.length)
    radios.forEach((radio, index) => {
      if (index === checked) {
        expect(radio).toBeChecked()
      } else {
        expect(radio).not.toBeChecked()
      }
    })
  })

  it('회차가 하나도 없으면 회차가 없다는 안내만 보인다', async () => {
    serveDetail(detail(Date.now(), []))
    renderRoutes(routes, '/concerts/7')

    const panel = within(await bookingPanel())

    expect(panel.getByText('등록된 회차가 없어요')).toBeInTheDocument()
    expect(panel.queryByRole('radio')).not.toBeInTheDocument()
    expect(panel.queryByRole('link', { name: '예매하기' })).not.toBeInTheDocument()
  })

  it('공연 정보 탭에 회차 일정 표가 회차 수만큼 행으로 있고, 예매 안내 탭으로 바꾸면 예매 순서 4단계가 보인다', async () => {
    const now = Date.now()
    serveDetail(detail(now, [{}, { openAt: iso(now + HOUR) }, { saleStatus: 'CLOSED' }]))
    renderRoutes(routes, '/concerts/7')

    const infoTab = await screen.findByRole('tab', { name: '공연 정보' })
    expect(infoTab).toHaveAttribute('aria-selected', 'true')
    const table = screen.getByRole('table')
    // 머리글 행 하나 + 회차마다 한 행
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 3)
    expect(screen.queryByRole('heading', { name: '예매 순서' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: '예매 안내' }))

    expect(screen.getByRole('tab', { name: '예매 안내' })).toHaveAttribute('aria-selected', 'true')
    const order = screen.getByRole('heading', { level: 3, name: '예매 순서' }).closest('section')!
    const steps = within(order).getAllByRole('listitem')
    expect(steps).toHaveLength(4)
    expect(steps.map((step) => step.textContent)).toEqual([
      expect.stringContaining('대기열 입장'),
      expect.stringContaining('좌석 선택'),
      expect.stringContaining('결제'),
      expect.stringContaining('예매 완료'),
    ])
    expect(screen.queryByRole('table')).not.toBeInTheDocument()

    await userEvent.click(infoTab)

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '예매 순서' })).not.toBeInTheDocument()
  })

  it('없는 공연이면 목록으로 돌아가는 안내를 보인다', async () => {
    renderRoutes(routes, '/concerts/999')

    expect(await screen.findByText(/찾는 공연이 없어요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/')
  })

  it.each(['abc', '0x10', '1e3', '99999999999999999999'])(
    '주소의 공연 번호가 10진 정수가 아니면(%s) 요청하지 않고 같은 안내를 보인다',
    (concertId) => {
      const requests: Request[] = []
      mockServer.events.on('request:start', ({ request }) => {
        if (new URL(request.url).pathname.startsWith('/api/v1/concerts/')) {
          requests.push(request)
        }
      })
      renderRoutes(routes, `/concerts/${concertId}`)

      expect(screen.getByText(/찾는 공연이 없어요/)).toBeInTheDocument()
      expect(requests).toHaveLength(0)
    },
  )
})
