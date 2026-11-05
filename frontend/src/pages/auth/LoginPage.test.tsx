import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { expireSession, sessionStore } from '@/auth/session'
import { DEMO_USER, TEMPORARY_PASSWORD_USER } from '@/mocks/db'
import { recordRequests, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

async function fillLogin(email: string, password: string) {
  await userEvent.type(screen.getByLabelText('이메일'), email)
  await userEvent.type(screen.getByLabelText('비밀번호'), password)
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
}

describe('LoginPage', () => {
  it('로그인이 필요한 화면에서 왔으면 로그인한 뒤 그 화면으로 돌아간다', async () => {
    const { router } = renderRoutes(routes, '/me')

    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByText('로그인하면 보던 화면으로 돌아가요')).toBeInTheDocument()

    await fillLogin(DEMO_USER.email, DEMO_USER.password)

    expect(await screen.findByRole('heading', { name: '내 정보' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/me')
    expect(
      await within(await screen.findByRole('main')).findByText(DEMO_USER.email),
    ).toBeInTheDocument()
  })

  it('비어 있거나 형식이 틀리면 요청을 보내지 않고 칸 아래에 알린다', async () => {
    const loginRequests = recordRequests('/api/v1/auth/login')
    renderRoutes(routes, '/login')

    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('이메일을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('비밀번호를 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription('이메일을 입력해 주세요')
    expect(screen.getByLabelText('이메일')).toHaveFocus()
    expect(loginRequests).toHaveLength(0)
  })

  it('비밀번호가 틀리면 비밀번호 칸 아래에 알리고 초점을 옮긴 뒤 잠금 기준을 보인다', async () => {
    renderRoutes(routes, '/login')

    await fillLogin(DEMO_USER.email, 'wrong-password')

    const password = screen.getByLabelText('비밀번호')
    expect(await screen.findByText('이메일 또는 비밀번호를 확인해 주세요')).toBeInTheDocument()
    expect(password).toHaveAttribute('aria-invalid', 'true')
    expect(password).toHaveFocus()
    expect(password).toHaveAccessibleDescription(
      '이메일 또는 비밀번호를 확인해 주세요 비밀번호를 5번 틀리면 계정이 잠겨요',
    )
  })

  it('다섯 번 틀리면 잠금을 알리고, 그 뒤에는 맞는 비밀번호도 받지 않는다', async () => {
    renderRoutes(routes, '/login')
    await userEvent.type(screen.getByLabelText('이메일'), DEMO_USER.email)
    const password = screen.getByLabelText('비밀번호')

    for (let attempt = 1; attempt <= 5; attempt += 1) {
      await userEvent.clear(password)
      await userEvent.type(password, `wrong-${attempt}`)
      await userEvent.click(screen.getByRole('button', { name: '로그인' }))
    }

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('로그인을 여러 번 실패해 계정이 잠겼어요')
    expect(alert).toHaveTextContent(
      '임시 비밀번호를 받으면 로그인할 때 새 비밀번호로 바꿀 수 있어요',
    )
    expect(screen.queryByText('비밀번호를 5번 틀리면 계정이 잠겨요')).not.toBeInTheDocument()

    await userEvent.clear(password)
    await userEvent.type(password, DEMO_USER.password)
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('계정이 잠겼어요')
    expect(sessionStore.getState().refreshToken).toBeNull()
  })

  it('임시 비밀번호로 로그인하면 이메일을 채운 비밀번호 변경 화면으로 보내고, 바꾸면 바로 로그인돼 원래 화면으로 간다', async () => {
    const { router } = renderRoutes(routes, '/me')

    await fillLogin(TEMPORARY_PASSWORD_USER.email, TEMPORARY_PASSWORD_USER.password)

    expect(await screen.findByRole('heading', { name: '비밀번호 변경' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/password-change')
    expect(screen.getByText('임시 비밀번호로는 로그인할 수 없어요')).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toHaveValue(TEMPORARY_PASSWORD_USER.email)

    await userEvent.type(screen.getByLabelText('임시 비밀번호'), TEMPORARY_PASSWORD_USER.password)
    await userEvent.type(screen.getByLabelText('새 비밀번호'), 'new-password-5678')
    await userEvent.type(screen.getByLabelText('새 비밀번호 확인'), 'new-password-5678')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 바꾸기' }))

    expect(await screen.findByRole('heading', { name: '내 정보' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/me')
    expect(await screen.findByText('비밀번호를 바꿨어요')).toBeInTheDocument()
  })

  it('로그인하면 이전 사용자의 조회 결과를 지운다', async () => {
    const { queryClient } = renderRoutes(routes, '/login')
    queryClient.setQueryData(['users', 'me'], { email: 'previous@iseonjwa.dev' })

    await fillLogin(DEMO_USER.email, DEMO_USER.password)

    await screen.findByRole('button', { name: '로그아웃' })
    expect(queryClient.getQueryData(['users', 'me'])).toBeUndefined()
  })

  it('처음 화면에서 왔으면 돌아간다는 안내 대신 기본 안내를 보인다', async () => {
    renderRoutes(routes, '/')

    await userEvent.click(screen.getByRole('link', { name: '로그인' }))

    expect(screen.getByText('예매하려면 로그인해 주세요')).toBeInTheDocument()
  })

  it('재발급이 거절돼 세션이 끝났으면 다시 로그인하라고 알린다', async () => {
    expireSession()
    renderRoutes(routes, '/login')

    expect(screen.getByRole('alert')).toHaveTextContent('로그인이 끝났어요. 다시 로그인해 주세요')

    // 한 번 제출한 뒤에는 칸 아래 안내에 집중하도록 숨긴다
    await fillLogin(DEMO_USER.email, 'wrong-password')

    expect(await screen.findByText('이메일 또는 비밀번호를 확인해 주세요')).toBeInTheDocument()
    expect(screen.queryByText('로그인이 끝났어요. 다시 로그인해 주세요')).not.toBeInTheDocument()
  })

  it('이미 로그인했으면 처음 화면으로 보낸다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/login')

    expect(router.state.location.pathname).toBe('/')
  })

  it('회원가입 링크는 돌아갈 곳을 이어 넘긴다', async () => {
    const { router } = renderRoutes(routes, '/me')

    await userEvent.click(within(screen.getByRole('main')).getByRole('link', { name: '회원가입' }))

    expect(router.state.location.pathname).toBe('/signup')
    expect(router.state.location.state).toMatchObject({ from: '/me' })
  })
})
