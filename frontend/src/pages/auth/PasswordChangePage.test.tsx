import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { sessionStore } from '@/auth/session'
import { DEMO_USER, mockDb } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

async function fillPasswordChange(currentPassword: string, newPassword: string, email?: string) {
  if (email !== undefined) {
    await userEvent.type(screen.getByLabelText('이메일'), email)
  }
  await userEvent.type(screen.getByLabelText('현재 비밀번호'), currentPassword)
  await userEvent.type(screen.getByLabelText('새 비밀번호'), newPassword)
  await userEvent.type(screen.getByLabelText('새 비밀번호 확인'), newPassword)
  await userEvent.click(screen.getByRole('button', { name: '비밀번호 바꾸기' }))
}

describe('PasswordChangePage', () => {
  it('내 정보에서 바꾸면 새 비밀번호로 다시 로그인해 내 정보로 돌아간다', async () => {
    await signInAsDemo()
    const oldRefreshToken = sessionStore.getState().refreshToken
    const { router } = renderRoutes(routes, '/me')
    await screen.findByText(DEMO_USER.email)

    await userEvent.click(screen.getByRole('link', { name: '비밀번호 변경' }))
    expect(screen.getByLabelText('이메일')).toHaveValue(DEMO_USER.email)
    await fillPasswordChange(DEMO_USER.password, 'new-password-5678')

    expect(await screen.findByText('비밀번호를 바꿨어요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/me')
    // 서버는 바꾸면서 이전 Refresh 토큰을 폐기한다. 새로 로그인해 받은 토큰으로 이어 간다
    expect(sessionStore.getState().refreshToken).not.toBe(oldRefreshToken)
    expect(mockDb.users.find((user) => user.userId === 42)?.password).toBe('new-password-5678')
  })

  it('로그인한 채 바꾼 뒤 다시 로그인이 실패하면 폐기된 옛 세션을 지우고 로그인 화면으로 보낸다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post('/api/v1/auth/login', () =>
        fail(503, 'SERVICE_UNAVAILABLE', '일시적으로 서비스를 사용할 수 없습니다'),
      ),
    )
    const { router } = renderRoutes(routes, '/me')
    await screen.findByText(DEMO_USER.email)
    await userEvent.click(screen.getByRole('link', { name: '비밀번호 변경' }))

    await fillPasswordChange(DEMO_USER.password, 'new-password-5678')

    expect(
      await screen.findByText('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요'),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByLabelText('이메일')).toHaveValue(DEMO_USER.email)
    expect(sessionStore.getState().refreshToken).toBeNull()
  })

  it('현재 비밀번호 칸을 고치면 "현재 비밀번호와 다른 비밀번호" 오류가 지워진다', async () => {
    renderRoutes(routes, '/password-change')
    await fillPasswordChange(DEMO_USER.password, DEMO_USER.password, DEMO_USER.email)
    expect(
      await screen.findByText('현재 비밀번호와 다른 비밀번호를 입력해 주세요'),
    ).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('현재 비밀번호'), '0')

    expect(
      screen.queryByText('현재 비밀번호와 다른 비밀번호를 입력해 주세요'),
    ).not.toBeInTheDocument()
  })

  it('현재 비밀번호와 같은 새 비밀번호는 요청하지 않고 새 비밀번호 칸에 알린다', async () => {
    const changeRequests = recordRequests('/api/v1/auth/password-change')
    renderRoutes(routes, '/password-change')

    await fillPasswordChange(DEMO_USER.password, DEMO_USER.password, DEMO_USER.email)

    expect(
      await screen.findByText('현재 비밀번호와 다른 비밀번호를 입력해 주세요'),
    ).toBeInTheDocument()
    expect(changeRequests).toHaveLength(0)
  })

  it('현재 비밀번호가 틀리면 그 칸에 알리고 잠금 기준을 보인다', async () => {
    renderRoutes(routes, '/password-change')

    await fillPasswordChange('wrong-password', 'new-password-5678', DEMO_USER.email)

    const current = screen.getByLabelText('현재 비밀번호')
    expect(await screen.findByText('이메일 또는 현재 비밀번호를 확인해 주세요')).toBeInTheDocument()
    expect(current).toHaveFocus()
    expect(screen.getByText('비밀번호를 5번 틀리면 계정이 잠겨요')).toBeInTheDocument()
  })

  it('잠긴 계정은 폼 위에 잠금을 알린다', async () => {
    const demo = mockDb.users.find((user) => user.userId === 42)
    if (demo !== undefined) {
      demo.locked = true
    }
    renderRoutes(routes, '/password-change')

    await fillPasswordChange(DEMO_USER.password, 'new-password-5678', DEMO_USER.email)

    expect(await screen.findByRole('alert')).toHaveTextContent('계정이 잠겼어요')
  })

  it('로그인 전이면 로그인 링크를 보인다', () => {
    renderRoutes(routes, '/password-change')

    const main = within(screen.getByRole('main'))
    expect(main.getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
  })

  it('로그인한 채 들어왔으면 바꾸지 않고 원래 화면으로 돌아가는 링크를 보인다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/me')
    await screen.findByText(DEMO_USER.email)
    await userEvent.click(screen.getByRole('link', { name: '비밀번호 변경' }))

    await userEvent.click(screen.getByRole('link', { name: '바꾸지 않고 돌아가기' }))

    expect(router.state.location.pathname).toBe('/me')
  })
})
