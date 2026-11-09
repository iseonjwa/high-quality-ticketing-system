import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

const PATH = '/help'

function recordAskRequests(): number[] {
  const calls: number[] = []
  mockServer.events.on('request:start', ({ request }) => {
    if (
      request.method === 'POST' &&
      new URL(request.url).pathname === '/api/v1/assistant/messages'
    ) {
      calls.push(Date.now())
    }
  })
  return calls
}

describe('예매 문의', () => {
  it('로그인하지 않으면 로그인 화면으로 보낸다', async () => {
    const { router } = renderRoutes(routes, PATH)

    await screen.findByRole('button', { name: '로그인' })
    expect(router.state.location.pathname).toBe('/login')
  })

  it('화면을 열 때는 요청하지 않고, 예시 질문을 누르면 AI 답변과 출처를 보인다', async () => {
    const user = userEvent.setup()
    const calls = recordAskRequests()
    await signInAsDemo()
    renderRoutes(routes, PATH)

    await screen.findByRole('heading', { name: '예매 문의' })
    expect(calls).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: '결제가 늦게 되면 환불되나요?' }))

    const conversation = await screen.findByRole('list', { name: '대화' })
    expect(within(conversation).getByText('결제가 늦게 되면 환불되나요?')).toBeInTheDocument()
    expect(within(conversation).getByText('AI 답변')).toBeInTheDocument()
    expect(within(conversation).getByText('결제 > 시간이 지난 뒤 승인된 결제')).toBeInTheDocument()
    expect(calls).toHaveLength(1)
  })

  it('검색 모드로 강등되면 이유와 관련 안내를 보인다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, PATH)

    await user.type(await screen.findByLabelText('질문'), '대기 순번 지연')
    await user.click(screen.getByRole('button', { name: '물어보기' }))

    expect(
      await screen.findByText('AI 답변이 잠시 어려워 관련 안내만 보여 드려요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('관련 안내')).toBeInTheDocument()
    expect(screen.getByText('대기열 > 순번과 입장')).toBeInTheDocument()
  })

  it('빈 질문은 보내지 않고 칸 아래에 알린다', async () => {
    const user = userEvent.setup()
    const calls = recordAskRequests()
    await signInAsDemo()
    renderRoutes(routes, PATH)

    await user.click(await screen.findByRole('button', { name: '물어보기' }))

    expect(screen.getByText('질문을 입력해 주세요')).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })

  it('요청이 많으면(429) 기다릴 시간을 알리고 다시 보내지 않는다', async () => {
    const user = userEvent.setup()
    const calls = recordAskRequests()
    mockServer.use(
      http.post('/api/v1/assistant/messages', () =>
        fail(429, 'RATE_LIMITED', '요청이 너무 많습니다', { headers: { 'Retry-After': '30' } }),
      ),
    )
    await signInAsDemo()
    renderRoutes(routes, PATH)

    await user.type(await screen.findByLabelText('질문'), '환불')
    await user.click(screen.getByRole('button', { name: '물어보기' }))

    expect(await screen.findByText(/30초 뒤 다시 물어봐 주세요/)).toBeInTheDocument()
    expect(calls).toHaveLength(1)
  })

  it('상담 서비스가 꺼져 있으면(503) 예매 안내로 안내한다', async () => {
    const user = userEvent.setup()
    mockServer.use(
      http.post('/api/v1/assistant/messages', () =>
        fail(503, 'SERVICE_UNAVAILABLE', '서비스를 사용할 수 없습니다'),
      ),
    )
    await signInAsDemo()
    renderRoutes(routes, PATH)

    await user.type(await screen.findByLabelText('질문'), '환불')
    await user.click(screen.getByRole('button', { name: '물어보기' }))

    expect(await screen.findByText('지금 상담을 쓸 수 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공연 보러 가기' })).toBeInTheDocument()
  })
})
