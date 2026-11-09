import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/routes'
import { saveEntryToken } from '@/auth/entryTokens'
import { formatDateTime } from '@/lib/format'
import { queueNavigationState } from '@/pages/queue/queueNavigation'
import { CONCERT_ID, ON_SALE_SCHEDULE_ID, signInAsDemo } from '@/test/api'

// 좌석 → 결제 → 결과 화면을 목 기본 시나리오로 한 번에 지난다.

const CONCERT = {
  concertId: CONCERT_ID,
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}
const DEMO_USER_ID = 42

beforeEach(async () => {
  await signInAsDemo()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('예매 흐름(좌석 → 결제 → 결과)', () => {
  it('좌석을 골라 잡고 결제하면 확정된 결과 화면에 티켓 번호와 공연 한 줄이 나온다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const expiresAtMillis = Date.now() + 10 * 60_000
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: `mock-entry.${ON_SALE_SCHEDULE_ID}.${DEMO_USER_ID}.${expiresAtMillis}`,
      expiresAt: new Date(expiresAtMillis).toISOString(),
    })
    const router = createMemoryRouter(routes, {
      initialEntries: [
        {
          pathname: `/schedules/${ON_SALE_SCHEDULE_ID}/seats`,
          state: queueNavigationState(CONCERT),
        },
      ],
    })
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    await screen.findByRole('radio', { name: /^A구역 VIP/ })
    await userEvent.click(screen.getByRole('button', { name: /A구역 1열 1번,/ }))
    await userEvent.click(screen.getByRole('button', { name: /A구역 1열 2번,/ }))
    await userEvent.click(screen.getByRole('button', { name: '2매 선택 완료' }))
    const dialog = await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    await userEvent.click(within(dialog).getByRole('button', { name: '결제하러 가기' }))
    await userEvent.click(await screen.findByRole('button', { name: /원 결제하기$/ }))

    // 승인 뒤 확정은 예매 조회를 몇 번 한 뒤에 반영된다. 가짜 시계를 나눠 돌려 기다린다
    for (let elapsed = 0; !router.state.location.pathname.endsWith('/complete'); elapsed += 1000) {
      if (elapsed > 60_000) {
        throw new Error('결과 화면에 도착하지 않았다')
      }
      await act(() => vi.advanceTimersByTimeAsync(1000))
    }

    expect(
      await screen.findByRole('heading', { level: 1, name: '예매가 확정됐어요' }),
    ).toBeInTheDocument()
    const tickets = screen.getByRole('region', { name: '티켓 2매' })
    // 포스터 위 꾸밈 제목과 겹치지 않게 제목 줄(p)만 찾는다
    expect(within(tickets).getByText(CONCERT.title, { selector: 'p' })).toBeInTheDocument()
    expect(
      within(tickets).getByText(`${formatDateTime(CONCERT.startsAt)} · ${CONCERT.venueName}`),
    ).toBeInTheDocument()
    expect(within(tickets).getAllByText(/티켓 번호/)).toHaveLength(2)
    expect(within(tickets).getByText('예매 확정')).toBeInTheDocument()
    const steps = screen.getByRole('list', { name: '예매 단계' })
    expect(within(steps).getByRole('listitem', { current: 'step' })).toHaveTextContent('완료')
    expect(screen.getByRole('link', { name: '내 예매 목록' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
  })
})
