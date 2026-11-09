import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { readEntryToken, saveEntryToken } from '@/auth/entryTokens'
import { readQueueConcert } from '@/pages/queue/queueNavigation'
import { clearSession } from '@/auth/session'
import { setMockScenario } from '@/mocks/scenario'
import { ok } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import {
  BEFORE_OPEN_SCHEDULE_ID,
  CONCERT_ID,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  signInAsDemo,
} from '@/test/api'
import { renderRoutes } from '@/test/render'

const CONCERT_TITLE = '노을섬 단독 콘서트 〈새벽의 좌석〉'
const QUEUE_PATH = `/schedules/${ON_SALE_SCHEDULE_ID}/queue`
const SEATS_PATH = `/schedules/${ON_SALE_SCHEDULE_ID}/seats`
const JOIN_API = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/queue`
const STEP_TIMEOUT = { timeout: 5000 }

// 로그인하지 않은 테스트는 따로 로그인 전 상태로 그린다
beforeEach(async () => {
  await signInAsDemo()
})

async function openFromConcertDetail() {
  const rendered = renderRoutes(routes, `/concerts/${CONCERT_ID}`)
  // 예매 패널에서 판매 중인 회차를 고르고 [예매하기] 링크를 누른다
  const radios = await screen.findAllByRole('radio')
  const onSale = radios.find((radio) => (radio as HTMLInputElement).value === '31')
  expect(onSale).toBeDefined()
  await userEvent.click(onSale!)
  const reserve = within(screen.getByRole('main'))
    .getAllByRole('link', { name: '예매하기' })
    .find((link) => link.getAttribute('href')?.endsWith('/schedules/31/queue'))
  expect(reserve).toBeDefined()
  await userEvent.click(reserve!)
  return rendered
}

describe('QueuePage', () => {
  it('공연 상세에서 들어오면 공연 줄과 순번을 보이고, 차례가 되면 좌석 화면으로 바꿔 들어간다', async () => {
    const { router } = await openFromConcertDetail()

    expect(await screen.findByText(CONCERT_TITLE)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(QUEUE_PATH)
    expect(await screen.findByText('내 순번')).toBeInTheDocument()
    expect(screen.getByText('순번을 자동으로 확인하고 있어요')).toBeInTheDocument()
    expect(screen.getByText(/한강 아트홀/)).toBeInTheDocument()

    await waitFor(() => expect(router.state.location.pathname).toBe(SEATS_PATH), STEP_TIMEOUT)
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).not.toBeNull()
    // 좌석 화면도 공연 줄을 그릴 수 있게 공연 요약을 넘긴다
    expect(readQueueConcert(router.state.location.state)?.title).toBe(CONCERT_TITLE)

    // 대기열이 history에 남지 않는다
    await act(() => router.navigate(-1))
    expect(router.state.location.pathname).toBe(`/concerts/${CONCERT_ID}`)
  }, 10_000)

  it('주소로 바로 열면 공연 줄 없이 그린다', async () => {
    renderRoutes(routes, QUEUE_PATH)

    expect(await screen.findByText('내 순번')).toBeInTheDocument()
    expect(screen.queryByText(CONCERT_TITLE)).not.toBeInTheDocument()
  })

  it('저장한 입장 토큰이 유효하면 요청 없이 바로 좌석 화면으로 간다', async () => {
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: 'stored-token',
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    })
    const joins = recordRequests(JOIN_API)
    const polls = recordRequests(`${JOIN_API}/me`)
    const { router } = renderRoutes(routes, QUEUE_PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe(SEATS_PATH))
    expect(joins).toHaveLength(0)
    expect(polls).toHaveLength(0)
  })

  it('대기열이 잠시 불안정하면 안내를 보였다가 연결되면 지우고 순번을 보인다', async () => {
    setMockScenario({ queue: 'unavailable' })
    renderRoutes(routes, QUEUE_PATH)

    // 알림 영역은 처음부터 있고 글자만 바뀐다(내용을 담은 채 새로 붙는 영역은 읽히지 않을 수 있다)
    const regions = screen.getAllByRole('status')
    const unstable = await screen.findByText('연결이 잠시 불안정해요. 곧 다시 시도할게요')
    expect(regions.some((region) => region.contains(unstable))).toBe(true)
    expect(await screen.findByText('내 순번', undefined, STEP_TIMEOUT)).toBeInTheDocument()
    expect(screen.queryByText('연결이 잠시 불안정해요. 곧 다시 시도할게요')).not.toBeInTheDocument()
  })

  it('입장 시간이 지나 빠지면 제목에 초점을 두고, 다시 줄 서기는 카드로 초점을 옮긴 뒤 좌석으로 간다', async () => {
    setMockScenario({ queue: 'expired' })
    const { router } = renderRoutes(routes, QUEUE_PATH)

    const title = await screen.findByRole(
      'heading',
      { name: '입장 시간이 지나 줄에서 빠졌어요' },
      STEP_TIMEOUT,
    )
    expect(title).toHaveFocus()
    // 공연 줄이 없으면 보조 링크는 목록이다
    expect(screen.getByRole('link', { name: '공연 목록으로' })).toHaveAttribute('href', '/')

    const card = document.querySelector<HTMLElement>('[tabindex="-1"][data-slot="card"]')
    expect(card).not.toBeNull()
    // 초점을 받는 순간 카드가 이미 새 내용이어야 방금 본 안내를 다시 읽지 않는다
    let textOnFocus = ''
    card!.addEventListener('focus', () => {
      textOnFocus = card!.textContent ?? ''
    })

    await userEvent.click(screen.getByRole('button', { name: '다시 줄 서기' }))

    expect(card).toHaveFocus()
    expect(textOnFocus).toContain('줄을 서고 있어요')
    expect(textOnFocus).not.toContain('입장 시간이 지나')
    expect(await screen.findByText('내 순번')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.pathname).toBe(SEATS_PATH), STEP_TIMEOUT)
  }, 12_000)

  it('공연 상세에서 왔으면 입장 시간이 지났을 때 공연 정보로 돌아가는 링크를 준다', async () => {
    setMockScenario({ queue: 'expired' })
    await openFromConcertDetail()

    await screen.findByRole('heading', { name: '입장 시간이 지나 줄에서 빠졌어요' }, STEP_TIMEOUT)

    expect(screen.getByRole('link', { name: '공연 정보로 돌아가기' })).toHaveAttribute(
      'href',
      `/concerts/${CONCERT_ID}`,
    )
  }, 10_000)

  it('오픈 전 회차면 제목에 초점을 두고 돌아가기가 주 행동이며 다시 시도 버튼은 없다', async () => {
    renderRoutes(routes, `/schedules/${BEFORE_OPEN_SCHEDULE_ID}/queue`)

    const title = await screen.findByRole('heading', { name: '아직 오픈 전이에요' })
    expect(title).toHaveFocus()
    expect(screen.getByRole('link', { name: '공연 목록으로' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 줄 서기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('없는 회차면 다시 시도 없이 공연 목록으로 돌아가게 한다', async () => {
    renderRoutes(routes, '/schedules/999/queue')

    expect(await screen.findByRole('heading', { name: '찾는 정보가 없어요' })).toBeInTheDocument()
    expect(screen.getByText('목록에서 다시 골라 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공연 목록으로' })).toHaveAttribute('href', '/')
  })

  it('다음 입장 묶음 안(예상 대기 1초 이하)이면 순번 대신 곧 입장한다고 보인다', async () => {
    mockServer.use(
      http.post(JOIN_API, () =>
        ok({
          status: 'WAITING',
          position: 1,
          estimatedWaitSeconds: 1,
          nextPollAfterMillis: 60_000,
        }),
      ),
    )
    renderRoutes(routes, QUEUE_PATH)

    expect(await screen.findByRole('heading', { name: '곧 입장해요' })).toBeInTheDocument()
    expect(screen.getByText('좌석 선택 화면을 준비하고 있어요.')).toBeInTheDocument()
    expect(screen.queryByText('내 순번')).not.toBeInTheDocument()
  })

  it('예상하지 못한 오류면 문의 번호를 보이고 다시 시도하면 진입 요청을 다시 보낸다', async () => {
    mockServer.use(
      http.post(JOIN_API, () =>
        HttpResponse.json(
          { status: 400, code: 'SOMETHING_NEW', message: '모르는 오류', traceId: 'trace-queue-1' },
          { status: 400 },
        ),
      ),
    )
    const joins = recordRequests(JOIN_API)
    renderRoutes(routes, QUEUE_PATH)

    expect(await screen.findByRole('heading', { name: '문제가 생겼어요' })).toBeInTheDocument()
    expect(screen.getByText('문의 번호 trace-queue-1')).toBeInTheDocument()
    expect(joins).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(joins).toHaveLength(2))
  })

  it('주소의 회차 번호가 숫자가 아니면 안내만 보이고 대기열 요청을 보내지 않는다', async () => {
    const joins = recordRequests('/api/v1/schedules/abc/queue')
    renderRoutes(routes, '/schedules/abc/queue')

    expect(
      await screen.findByText('찾는 회차가 없어요. 목록에서 다시 골라 주세요'),
    ).toBeInTheDocument()
    expect(joins).toHaveLength(0)
  })
})

describe('QueuePage 로그인 전', () => {
  it('로그인하지 않았으면 대기열 경로를 돌아올 곳으로 들고 로그인 화면으로 간다', async () => {
    clearSession()
    const { router } = renderRoutes(routes, QUEUE_PATH)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toEqual({ from: QUEUE_PATH })
  })
})
