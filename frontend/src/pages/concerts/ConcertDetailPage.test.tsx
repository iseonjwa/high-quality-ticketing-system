import { act, screen, within } from '@testing-library/react'
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

describe('ConcertDetailPage', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  // 완료 기준: 브라우저 시계를 바꿔도 서버 시각 기준, 오픈 시각이 되면 새로고침 없이 [예매하기]가 켜진다
  it('브라우저 시계가 서버보다 1시간 늦어도 서버 시각으로 세고, 0이 되면 새로고침 없이 예매하기가 켜진다', async () => {
    // 시계와 주기 타이머만 가짜로 돌린다. 목 서버와 조회 재시도가 쓰는 setTimeout은 그대로 둔다
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    const serverNow = Date.now() + HOUR
    serveDetail(
      detail(serverNow, [{ openAt: iso(serverNow + 3 * SECOND), saleStatus: 'BEFORE_OPEN' }]),
    )
    const detailRequests = recordRequests('/api/v1/concerts/7')
    renderRoutes(routes, '/concerts/7')

    const timer = await screen.findByRole('timer', { name: '오픈까지 남은 시간' })
    expect(timer).toHaveTextContent('00:00:03')
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()

    act(() => {
      vi.advanceTimersByTime(2 * SECOND)
    })
    expect(timer).toHaveTextContent('00:00:01')
    expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled()

    act(() => {
      vi.advanceTimersByTime(SECOND)
    })
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

  it('판매 중이면 예매하기 링크를, 서버가 판매 종료라고 하면 안내만 보인다', async () => {
    const now = Date.now()
    serveDetail(detail(now, [{}, { saleStatus: 'CLOSED' }]))
    renderRoutes(routes, '/concerts/7')

    const [onSale, closed] = await screen.findAllByRole('listitem')
    expect(within(onSale).getByText('예매 중')).toBeInTheDocument()
    expect(within(onSale).getByRole('link', { name: '예매하기' })).toBeInTheDocument()
    // 시각으로는 판매 중이어도 서버가 CLOSED면 따른다
    expect(within(closed).getByText('판매 종료')).toBeInTheDocument()
    expect(within(closed).getByText('판매가 끝났어요')).toBeInTheDocument()
    expect(within(closed).queryByRole('link', { name: '예매하기' })).not.toBeInTheDocument()
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
