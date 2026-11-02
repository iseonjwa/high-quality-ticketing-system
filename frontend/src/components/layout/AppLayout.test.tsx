import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { routes } from '@/app/routes'
import { AppLayout } from '@/components/layout/AppLayout'
import { RouteErrorPage } from '@/components/layout/RouteErrorPage'
import { sessionStore } from '@/auth/session'
import { signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

describe('AppLayout', () => {
  it('로그인 전에는 로그인 링크를 보인다', () => {
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('로그인 뒤에는 내 예매 링크와 로그아웃 버튼을 보인다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '내 예매' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })

  it('로그아웃하면 세션을 지우고 캐시를 비운 뒤 홈에서 로그인 링크를 다시 보인다', async () => {
    await signInAsDemo()
    const { router, queryClient } = renderRoutes(routes, '/no-such-page')
    queryClient.setQueryData(['concerts'], ['이전 사용자의 조회 결과'])

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStore.getState().refreshToken).toBeNull()
    expect(queryClient.getQueryData(['concerts'])).toBeUndefined()
    expect(router.state.location.pathname).toBe('/')
    expect(await screen.findByText('로그아웃했어요')).toBeInTheDocument()
  })

  it('워드마크는 제목 요소가 아니라 홈 링크다', () => {
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '이선좌' })).toHaveAttribute('href', '/')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })
})

describe('RouteErrorPage', () => {
  function ThrowingPage(): never {
    throw new ApiError({
      status: 500,
      code: 'INTERNAL_SERVER_ERROR',
      message: '서버 오류',
      traceId: 'trace-abc-123',
    })
  }

  it('자식 경로가 던진 ApiError를 문구와 문의 번호로 보인다', () => {
    renderRoutes(
      [
        {
          element: <AppLayout />,
          errorElement: <RouteErrorPage />,
          children: [
            {
              errorElement: <RouteErrorPage />,
              children: [{ path: '/boom', element: <ThrowingPage /> }],
            },
          ],
        },
      ],
      '/boom',
    )

    expect(screen.getByText(/문의할 때 아래 번호를 알려 주세요/)).toBeInTheDocument()
    expect(screen.getByText('trace-abc-123')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '처음으로' })).toBeInTheDocument()
    // 에러 화면에서도 헤더는 남는다
    expect(screen.getByRole('link', { name: '이선좌' })).toBeInTheDocument()
  })
})
