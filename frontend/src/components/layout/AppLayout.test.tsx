import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { NonIndexRouteObject, RouteObject } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { routes } from '@/app/routes'
import { sessionStore } from '@/auth/session'
import { enableDemoMode } from '@/demo/demoMode'
import { mockServer } from '@/mocks/server'
import { recordRequests, requestStarted, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

describe('AppLayout', () => {
  it('로그인 전에는 로그인 링크를 보인다', () => {
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('로그인 뒤에는 내 예매·내 정보 링크와 로그아웃 버튼을 보인다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '내 예매' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '내 정보' })).toHaveAttribute('href', '/me')
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })

  it('헤더의 로그인 링크는 지금 보는 화면을 돌아갈 곳으로 넘긴다', async () => {
    const { router } = renderRoutes(routes, '/no-such-page?from=header')

    await userEvent.click(screen.getByRole('link', { name: '로그인' }))

    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/no-such-page?from=header' })
  })

  it('로그인이 필요한 화면에서 로그아웃하면 로그인 화면을 거치지 않고 처음 화면으로 간다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/me')
    const visited: string[] = []
    router.subscribe((state) => visited.push(state.location.pathname))

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByText('로그아웃했어요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
    expect(visited).not.toContain('/login')
  })

  it('로그아웃하면 세션을 지우고 캐시를 비운 뒤 홈에서 로그인 링크를 다시 보인다', async () => {
    await signInAsDemo()
    const { router, queryClient } = renderRoutes(routes, '/no-such-page')
    queryClient.setQueryData(['users', 'me'], { email: '이전 사용자의 조회 결과' })

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(sessionStore.getState().refreshToken).toBeNull()
    expect(queryClient.getQueryData(['users', 'me'])).toBeUndefined()
    expect(router.state.location.pathname).toBe('/')
    expect(await screen.findByText('로그아웃했어요')).toBeInTheDocument()
  })

  it('처음 화면에서 로그아웃해도 공개 조회인 공연 목록은 그대로 두어 깜빡이지 않는다', async () => {
    await signInAsDemo()
    const listRequests = recordRequests('/api/v1/concerts')
    const { queryClient } = renderRoutes(routes, '/')
    await screen.findByRole('link', { name: '노을섬 단독 콘서트 〈새벽의 좌석〉' })
    queryClient.setQueryData(['users', 'me'], { email: '이전 사용자의 조회 결과' })

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByText('로그아웃했어요')).toBeInTheDocument()
    expect(queryClient.getQueryData(['users', 'me'])).toBeUndefined()
    expect(
      screen.getByRole('link', { name: '노을섬 단독 콘서트 〈새벽의 좌석〉' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('공연 목록을 불러오고 있어요')).not.toBeInTheDocument()
    expect(listRequests).toHaveLength(1)
  })

  it('로그아웃 요청을 기다리는 동안에는 로그인한 사용자 메뉴를 누를 수 없다', async () => {
    await signInAsDemo()
    let finishLogout: () => void = () => {}
    mockServer.use(
      http.post(
        '/api/v1/auth/logout',
        () =>
          new Promise<Response>((resolve) => {
            finishLogout = () =>
              resolve(HttpResponse.json({ status: 200, message: '', data: null, traceId: 't' }))
          }),
      ),
    )
    const { router } = renderRoutes(routes, '/me')
    const logoutStarted = requestStarted('/api/v1/auth/logout')

    await userEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await logoutStarted
    const myInfo = screen.getByRole('link', { name: '내 정보' })
    expect(myInfo).toHaveAttribute('aria-disabled', 'true')
    myInfo.click()
    expect(router.state.location.pathname).toBe('/')

    finishLogout()
    expect(await screen.findByText('로그아웃했어요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
  })

  it('예매 흐름 화면(대기열)에서는 헤더에 예매 단계를 보이고 대기열을 현재 단계로 표시한다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/schedules/31/queue')

    const steps = await screen.findByRole('list', { name: '예매 단계' })
    const current = within(steps).getByRole('listitem', { current: 'step' })
    expect(current).toHaveTextContent('대기열')
  })

  it('공연 목록에서는 예매 단계를 보이지 않는다', async () => {
    renderRoutes(routes, '/')

    await screen.findByRole('heading', { level: 1, name: '공연' })
    expect(screen.queryByRole('list', { name: '예매 단계' })).not.toBeInTheDocument()
  })

  it('워드마크는 제목 요소가 아니라 홈 링크다', () => {
    renderRoutes(routes, '/')

    expect(screen.getByRole('link', { name: '이선좌' })).toHaveAttribute('href', '/')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })
})

describe('AppLayout 시연 모드', () => {
  const root = document.documentElement
  const originalClientWidth = Object.getOwnPropertyDescriptor(root, 'clientWidth')

  afterEach(() => {
    if (originalClientWidth === undefined) {
      Reflect.deleteProperty(root, 'clientWidth')
    } else {
      Object.defineProperty(root, 'clientWidth', originalClientWidth)
    }
  })

  // 패널은 lazy라 렌더 직후에는 켜져 있어도 없다. 화면이 다 그려진 뒤에 확인한다
  it('시연 모드가 꺼져 있으면 요청 로그 패널이 없다', async () => {
    Object.defineProperty(root, 'clientWidth', { configurable: true, value: 1440 })
    renderRoutes(routes, '/')

    expect(await screen.findByRole('heading', { level: 1, name: '공연' })).toBeInTheDocument()
    // 켜져 있었다면 패널 모듈을 받은 뒤 그려진다. 같은 모듈을 기다린 뒤 확인해 시점에 기대지 않는다
    await act(async () => {
      await import('@/demo/RequestLogPanel')
    })
    expect(screen.queryByRole('complementary', { name: '요청 로그 (시연 모드)' })).toBeNull()
  })

  it('시연 모드가 켜져 있으면 요청 로그 패널이 나타난다', async () => {
    Object.defineProperty(root, 'clientWidth', { configurable: true, value: 1440 })
    enableDemoMode()
    renderRoutes(routes, '/')

    expect(
      await screen.findByRole('complementary', { name: '요청 로그 (시연 모드)' }),
    ).toBeInTheDocument()
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

  // 실제 경로 목록의 화면 자리에 오류를 던지는 화면을 하나 끼운다. 안쪽 errorElement가 빠지면 헤더가 사라져 실패한다.
  function routesWithThrowingPage(): RouteObject[] {
    // 레이아웃과 화면 묶음 경로는 index 경로가 아니다
    const layout = routes[0] as NonIndexRouteObject
    const screens = layout.children?.[0] as NonIndexRouteObject
    return [
      {
        ...layout,
        children: [
          {
            ...screens,
            children: [{ path: '/boom', element: <ThrowingPage /> }, ...(screens.children ?? [])],
          },
        ],
      },
    ]
  }

  it('화면이 던진 ApiError를 문구와 문의 번호로 보이고 헤더는 남긴다', () => {
    renderRoutes(routesWithThrowingPage(), '/boom')

    expect(screen.getByText(/문의할 때 아래 번호를 알려 주세요/)).toBeInTheDocument()
    expect(screen.getByText('trace-abc-123')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '처음으로' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '이선좌' })).toBeInTheDocument()
  })

  it('로그아웃을 두 번 눌러도 요청은 한 번만 나간다', async () => {
    await signInAsDemo()
    const logoutRequests = recordRequests('/api/v1/auth/logout')
    renderRoutes(routes, '/')
    const button = screen.getByRole('button', { name: '로그아웃' })

    await userEvent.dblClick(button)

    expect(await screen.findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(logoutRequests).toHaveLength(1)
  })
})
