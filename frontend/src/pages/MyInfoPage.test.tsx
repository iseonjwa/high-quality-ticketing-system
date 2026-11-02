import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { sessionStore } from '@/auth/session'
import { DEMO_USER, expireMockAccessTokens, mockDb } from '@/mocks/db'
import { signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

describe('MyInfoPage', () => {
  it('내 이메일·닉네임·회원 구분을 보인다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    // 불러오는 동안은 화면 낭독기가 읽는 상태 문구를 둔다
    expect(screen.getByText('내 정보를 불러오고 있어요').closest('[role="status"]')).not.toBeNull()
    expect(await screen.findByText(DEMO_USER.email)).toBeInTheDocument()
    expect(screen.getByText('데모')).toBeInTheDocument()
    expect(screen.getByText('일반 회원')).toBeInTheDocument()
  })

  it('로그인 전이면 지금 주소를 돌아갈 곳으로 넘기고 로그인 화면으로 보낸다', () => {
    const { router } = renderRoutes(routes, '/me?tab=info')

    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/me?tab=info' })
  })

  it('보던 중 세션이 끝나면 로그인 화면으로 보내고 다시 로그인하라고 알린다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    // 다른 기기에서 로그인해 이 탭의 Refresh 토큰이 바뀌었다
    mockDb.refreshTokens.set(42, 'mock-refresh.42.999')
    const { router } = renderRoutes(routes, '/me')

    expect(await screen.findByText('로그인이 끝났어요. 다시 로그인해 주세요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/me' })
    expect(sessionStore.getState().refreshToken).toBeNull()
  })
})
