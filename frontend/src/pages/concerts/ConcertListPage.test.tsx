import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { fail, ok } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests } from '@/test/api'
import { renderRoutes } from '@/test/render'

const MINUTE = 60 * 1000
const DAY = 24 * 60 * MINUTE

function summary(concertId: number, title: string, openInMillis: number | null, serverNow: number) {
  return {
    concertId,
    title,
    nextStartsAt: openInMillis === null ? null : new Date(serverNow + 10 * DAY).toISOString(),
    nextOpenAt: openInMillis === null ? null : new Date(serverNow + openInMillis).toISOString(),
  }
}

describe('ConcertListPage', () => {
  it('공연마다 판매 상태와 공연·오픈 일시를 보이고, 누르면 상세로 간다', async () => {
    const { router } = renderRoutes(routes, '/')

    const cards = await screen.findAllByRole('listitem')
    expect(cards).toHaveLength(3)
    const nightSky = within(cards[0])
    expect(
      nightSky.getByRole('heading', { name: '미드나잇 오케스트라 〈별의 지도〉' }),
    ).toBeInTheDocument()
    // 다음 회차가 없으면 끝났는지 등록 전인지 알 수 없어 배지를 달지 않는다
    expect(nightSky.queryByText('판매 종료')).not.toBeInTheDocument()
    expect(nightSky.getByText('예정된 회차가 없어요')).toBeInTheDocument()
    expect(within(cards[1]).getByText('오픈 예정')).toBeInTheDocument()
    expect(within(cards[2]).getByText('예매 중')).toBeInTheDocument()
    expect(within(cards[2]).getByText('공연')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: '노을섬 단독 콘서트 〈새벽의 좌석〉' }))

    expect(router.state.location.pathname).toBe('/concerts/7')
  })

  it('오픈 전 공연은 오픈 예정 배지를 보인다', async () => {
    const now = Date.now()
    mockServer.use(
      http.get('/api/v1/concerts', () =>
        ok({
          content: [summary(7, '오픈 전 공연', DAY, now)],
          page: 0,
          size: 20,
          totalElements: 1,
        }),
      ),
    )
    renderRoutes(routes, '/')

    expect(await screen.findByText('오픈 예정')).toBeInTheDocument()
  })

  it('공연이 없으면 빈 상태를 보인다', async () => {
    mockServer.use(
      http.get('/api/v1/concerts', () => ok({ content: [], page: 0, size: 20, totalElements: 0 })),
    )
    renderRoutes(routes, '/')

    expect(await screen.findByText('아직 예매할 수 있는 공연이 없어요')).toBeInTheDocument()
  })

  it('마지막 페이지를 넘는 주소면 첫 페이지로 가는 안내를 보인다', async () => {
    mockServer.use(
      http.get('/api/v1/concerts', () => ok({ content: [], page: 4, size: 20, totalElements: 3 })),
    )
    renderRoutes(routes, '/?page=5')

    expect(await screen.findByText('이 페이지에는 공연이 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '첫 페이지로' })).toHaveAttribute('href', '/')
  })

  it('받은 뒤 다시 조회하다 실패해도 목록을 그대로 둔다', async () => {
    const { queryClient } = renderRoutes(routes, '/')
    await screen.findByRole('link', { name: '노을섬 단독 콘서트 〈새벽의 좌석〉' })

    mockServer.use(
      http.get('/api/v1/concerts', () => fail(429, 'RATE_LIMITED', '요청이 너무 많습니다')),
    )
    // Note: TanStack Query는 화면 갱신을 다음 매크로태스크로 미룬다. 기다리지 않고 검사하면 갱신 전 화면을 본다
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['concerts', 'list', 0] })
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(queryClient.getQueryState(['concerts', 'list', 0])?.status).toBe('error')

    expect(
      screen.getByRole('link', { name: '노을섬 단독 콘서트 〈새벽의 좌석〉' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  // 5xx는 조회 기본값으로 두 번 더 시도하므로 바로 실패하는 429로 확인한다
  it('조회에 실패하면 알림과 다시 시도를 보인다', async () => {
    mockServer.use(
      http.get('/api/v1/concerts', () => fail(429, 'RATE_LIMITED', '요청이 너무 많습니다')),
    )
    renderRoutes(routes, '/')

    expect(await screen.findByText('요청이 많아요. 잠시 뒤 다시 시도해 주세요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('한 페이지를 넘으면 페이지 이동을 보이고, 주소의 page는 1부터 센다', async () => {
    const now = Date.now()
    const requests = recordRequests('/api/v1/concerts')
    mockServer.use(
      http.get('/api/v1/concerts', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        return ok({
          content: [summary(100 - page, `공연 ${page + 1}페이지`, DAY, now)],
          page,
          size: 20,
          totalElements: 41,
        })
      }),
    )
    renderRoutes(routes, '/?page=2')

    expect(await screen.findByText('공연 2페이지')).toBeInTheDocument()
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(new URL(requests[0].url).searchParams.get('page')).toBe('1')

    await userEvent.click(screen.getByRole('link', { name: '다음' }))

    expect(await screen.findByText('공연 3페이지')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  })
})
