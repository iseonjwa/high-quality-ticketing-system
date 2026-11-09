import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { sessionStore } from '@/auth/session'
import { DEMO_USER } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests } from '@/test/api'
import { renderRoutesReady } from '@/test/render'

interface SignupFields {
  email?: string
  password?: string
  passwordConfirm?: string
  nickname?: string
}

async function fillSignup(fields: SignupFields) {
  const { email = 'new@iseonjwa.dev', password = 'password1234', nickname = '새 회원' } = fields
  const passwordConfirm = fields.passwordConfirm ?? password
  await userEvent.type(screen.getByLabelText('이메일'), email)
  await userEvent.type(screen.getByLabelText('비밀번호'), password)
  await userEvent.type(screen.getByLabelText('비밀번호 확인'), passwordConfirm)
  await userEvent.type(screen.getByLabelText('닉네임'), nickname)
  await userEvent.click(screen.getByRole('button', { name: '가입하기' }))
}

describe('SignupPage', () => {
  it('가입하면 같은 값으로 바로 로그인해 처음 화면으로 간다', async () => {
    const { router } = await renderRoutesReady(routes, '/signup')

    await fillSignup({ email: '  New@Iseonjwa.dev ' })

    expect(await screen.findByText('가입을 마쳤어요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(sessionStore.getState().refreshToken).not.toBeNull()
  })

  it('입력 규칙에 맞지 않으면 요청하지 않고 칸마다 알린다', async () => {
    const signupRequests = recordRequests('/api/v1/users')
    await renderRoutesReady(routes, '/signup')

    await fillSignup({
      password: '가'.repeat(25),
      passwordConfirm: '나',
      nickname: '가'.repeat(31),
    })

    expect(
      await screen.findByText('너무 길어요. 영문은 72자, 한글은 24자까지 쓸 수 있어요'),
    ).toBeInTheDocument()
    expect(screen.getByText('비밀번호가 서로 달라요')).toBeInTheDocument()
    expect(screen.getByText('닉네임은 30자까지 쓸 수 있어요')).toBeInTheDocument()
    // 오류와 겹치는 도움말("8자 이상", "30자까지")은 오류가 있는 동안 숨긴다
    expect(screen.queryByText('8자 이상')).not.toBeInTheDocument()
    expect(signupRequests).toHaveLength(0)
  })

  it('이미 가입한 이메일이면 이메일 칸에 알리고, 그 이메일을 채운 로그인 화면으로 갈 수 있다', async () => {
    const { router } = await renderRoutesReady(routes, '/signup')

    await fillSignup({ email: DEMO_USER.email })

    expect(await screen.findByText('이미 가입한 이메일이에요. 로그인해 주세요')).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toHaveFocus()

    await userEvent.click(screen.getByRole('link', { name: '이 이메일로 로그인하기' }))

    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByLabelText('이메일')).toHaveValue(DEMO_USER.email)
  })

  it('서버가 칸별 검증 오류를 주면 그 칸 아래에 공통 안내를 보인다', async () => {
    mockServer.use(
      http.post('/api/v1/users', () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
          errors: [{ field: 'nickname', reason: 'must not be blank' }],
        }),
      ),
    )
    await renderRoutesReady(routes, '/signup')

    await fillSignup({})

    expect(await screen.findByText('입력한 값을 확인해 주세요')).toBeInTheDocument()
    expect(screen.getByLabelText('닉네임')).toHaveAccessibleDescription('입력한 값을 확인해 주세요')
    expect(screen.queryByText('must not be blank')).not.toBeInTheDocument()
  })

  it('가입 뒤 자동 로그인이 실패하면 가입 실패로 보이지 않고 이메일을 채운 로그인 화면으로 보낸다', async () => {
    mockServer.use(
      http.post('/api/v1/auth/login', () =>
        fail(503, 'SERVICE_UNAVAILABLE', '일시적으로 서비스를 사용할 수 없습니다'),
      ),
    )
    const { router } = await renderRoutesReady(routes, '/signup')

    await fillSignup({ email: 'new@iseonjwa.dev' })

    expect(await screen.findByText('가입을 마쳤어요. 로그인해 주세요')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByLabelText('이메일')).toHaveValue('new@iseonjwa.dev')
  })

  it('비밀번호 칸을 확인 칸과 같게 고치면 "서로 달라요"가 지워진다', async () => {
    await renderRoutesReady(routes, '/signup')
    await fillSignup({ password: 'password1234', passwordConfirm: 'password12345' })
    expect(await screen.findByText('비밀번호가 서로 달라요')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('비밀번호'), '5')

    expect(screen.queryByText('비밀번호가 서로 달라요')).not.toBeInTheDocument()
  })

  it('예상하지 못한 오류는 폼 위에 문의 번호와 함께 알린다', async () => {
    mockServer.use(
      http.post('/api/v1/users', () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 오류')),
    )
    await renderRoutesReady(routes, '/signup')

    await fillSignup({})

    expect(await screen.findByRole('alert')).toHaveTextContent('문의할 때 아래 번호를 알려 주세요')
    expect(screen.getByText(/^문의 번호/)).toBeInTheDocument()
  })
})
