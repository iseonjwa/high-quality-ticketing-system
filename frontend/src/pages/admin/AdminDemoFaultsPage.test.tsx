import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { applyFaultRule } from '@/api/demoFaults'
import { mockServer } from '@/mocks/server'
import { signInAsAdmin, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

const PATH = '/admin/demo/faults'

// 핸들러의 장애 설정은 모듈 상태라 테스트마다 풀어 둔다
async function clearApprove() {
  await signInAsAdmin()
  await applyFaultRule({ target: 'APPROVE', mode: 'NONE' })
}

function recordFaultBodies(): unknown[] {
  const bodies: unknown[] = []
  mockServer.events.on('request:start', ({ request }) => {
    const { pathname } = new URL(request.url)
    if (request.method === 'POST' && pathname === '/api/v1/admin/demo/pg/faults') {
      void request
        .clone()
        .json()
        .then((body: unknown) => bodies.push(body))
    }
  })
  return bodies
}

describe('시연 장애 패널', () => {
  afterEach(async () => {
    await clearApprove()
  })

  it('운영자가 아니면 들어올 수 없다', async () => {
    await signInAsDemo()
    renderRoutes(routes, PATH)

    expect(await screen.findByText('운영자만 볼 수 있는 화면이에요')).toBeInTheDocument()
  })

  it('시나리오를 걸면 다음 결제 1건에만 걸고, 걸린 장애에 보인다', async () => {
    const user = userEvent.setup()
    const bodies = recordFaultBodies()
    await signInAsAdmin()
    renderRoutes(routes, PATH)

    expect(
      await screen.findByText('걸린 장애가 없어요. 결제가 정상으로 돌아요.'),
    ).toBeInTheDocument()
    const card = screen.getByRole('heading', { name: '결제 거절' }).closest('li')
    if (card === null) throw new Error('시나리오 카드가 없다')
    await user.click(within(card).getByRole('button', { name: '다음 결제 1건에 걸기' }))

    expect(await within(card).findByText('걸려 있음')).toBeInTheDocument()
    expect(await screen.findByText('승인 · 거절')).toBeInTheDocument()
    expect(screen.getByText('남은 횟수 1')).toBeInTheDocument()
    await waitFor(() =>
      expect(bodies).toContainEqual({ target: 'APPROVE', mode: 'DECLINE', count: 1 }),
    )
  })

  it('PG 응답 지연은 결제 서비스 시한(2초)보다 긴 4초로 건다', async () => {
    const user = userEvent.setup()
    const bodies = recordFaultBodies()
    await signInAsAdmin()
    renderRoutes(routes, PATH)

    const card = (await screen.findByRole('heading', { name: 'PG 응답 지연' })).closest('li')
    if (card === null) throw new Error('시나리오 카드가 없다')
    await user.click(within(card).getByRole('button', { name: '다음 결제 1건에 걸기' }))

    expect(await screen.findByText(/4초 지연/)).toBeInTheDocument()
    await waitFor(() =>
      expect(bodies).toContainEqual({
        target: 'APPROVE',
        mode: 'DELAY',
        delayMs: 4000,
        count: 1,
      }),
    )
  })

  it('모두 해제하면 걸린 장애를 NONE으로 되돌린다', async () => {
    const user = userEvent.setup()
    await signInAsAdmin()
    await applyFaultRule({ target: 'APPROVE', mode: 'SERVER_ERROR' })
    renderRoutes(routes, PATH)

    expect(await screen.findByText('해제할 때까지')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '모두 해제' }))

    expect(
      await screen.findByText('걸린 장애가 없어요. 결제가 정상으로 돌아요.'),
    ).toBeInTheDocument()
  })

  it('Mock PG가 장애 주입을 열지 않았으면(404) 버튼을 막고 이유를 알린다', async () => {
    mockServer.use(
      http.get('/api/v1/admin/demo/pg/faults', () =>
        HttpResponse.json({ status: 404, error: 'Not Found' }, { status: 404 }),
      ),
      http.get('/api/v1/admin/demo/pg/ledger/summary', () =>
        HttpResponse.json({ status: 404, error: 'Not Found' }, { status: 404 }),
      ),
    )
    await signInAsAdmin()
    renderRoutes(routes, PATH)

    expect(await screen.findByText('이 환경에서는 장애를 걸 수 없어요')).toBeInTheDocument()
    for (const button of screen.getAllByRole('button', { name: /걸기$/ })) {
      expect(button).toBeDisabled()
    }
  })

  it('원장 요약을 보여 준다', async () => {
    await signInAsAdmin()
    renderRoutes(routes, PATH)

    const summary = await screen.findByRole('heading', { name: 'Mock PG 원장' })
    const section = summary.closest('section')
    if (section === null) throw new Error('원장 영역이 없다')
    expect(await within(section).findByText('12건')).toBeInTheDocument()
    expect(within(section).getByText('1,980,000원')).toBeInTheDocument()
  })
})

describe('시연 장애 패널 경로 없음', () => {
  it('공통 형식 404(Gateway에 경로가 없어 다른 서비스가 받음)는 닫힌 환경으로 보지 않는다', async () => {
    mockServer.use(
      http.get('/api/v1/admin/demo/pg/faults', () =>
        HttpResponse.json(
          {
            status: 404,
            code: 'NOT_FOUND',
            message: '대상을 찾을 수 없습니다',
            traceId: '0'.repeat(32),
          },
          { status: 404 },
        ),
      ),
    )
    await signInAsAdmin()
    renderRoutes(routes, PATH)

    expect(await screen.findByRole('heading', { name: '지금 걸린 장애' })).toBeInTheDocument()
    expect(screen.queryByText('이 환경에서는 장애를 걸 수 없어요')).not.toBeInTheDocument()
  })
})
