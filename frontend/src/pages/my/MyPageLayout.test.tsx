import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { ADMIN_USER, DEMO_USER } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { signInAsAdmin, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

const menu = () => within(screen.getByRole('navigation', { name: '마이페이지 메뉴' }))

describe('MyPageLayout 메뉴', () => {
  it('내 정보 화면에서 메뉴 링크 셋을 보이고 내 정보·자동결제 카드가 지금 화면이다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    await screen.findByRole('heading', { name: '내 정보' })
    expect(menu().getByRole('link', { name: '예매 내역' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
    expect(menu().getByRole('link', { name: '내 정보·자동결제 카드' })).toHaveAttribute(
      'href',
      '/me',
    )
    expect(menu().getByRole('link', { name: '비밀번호 변경' })).toHaveAttribute(
      'href',
      '/password-change',
    )
    expect(menu().getByRole('link', { name: '내 정보·자동결제 카드' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(menu().getByRole('link', { name: '예매 내역' })).not.toHaveAttribute('aria-current')
    expect(menu().getAllByRole('link')).toHaveLength(3)
  })

  it('예매 내역 화면에서는 예매 내역만 지금 화면이다(내 정보 링크는 /me 정확히 일치할 때만)', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me/reservations')

    await screen.findByRole('heading', { level: 1, name: '내 예매' })
    expect(menu().getByRole('link', { name: '예매 내역' })).toHaveAttribute('aria-current', 'page')
    expect(menu().getByRole('link', { name: '내 정보·자동결제 카드' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('메뉴로 옮기면 지금 화면 표시가 따라 바뀐다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/me')
    await screen.findByRole('heading', { name: '내 정보' })

    await userEvent.click(menu().getByRole('link', { name: '예매 내역' }))

    expect(router.state.location.pathname).toBe('/me/reservations')
    expect(menu().getByRole('link', { name: '예매 내역' })).toHaveAttribute('aria-current', 'page')
    expect(menu().getByRole('link', { name: '내 정보·자동결제 카드' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('운영자가 아니면 운영 화면 링크가 없다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    await within(screen.getByRole('main')).findByText(DEMO_USER.email)
    expect(menu().queryByRole('link', { name: '운영 화면' })).not.toBeInTheDocument()
  })

  it('운영자에게는 운영 화면 링크가 /admin으로 보인다', async () => {
    await signInAsAdmin()
    renderRoutes(routes, '/me')

    expect(await menu().findByRole('link', { name: '운영 화면' })).toHaveAttribute('href', '/admin')
    expect(menu().getAllByRole('link')).toHaveLength(4)
  })

  it('비밀번호 변경 링크는 돌아올 곳(/me)과 내 이메일을 화면 이동 값으로 넘긴다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/me')
    await within(screen.getByRole('main')).findByText(DEMO_USER.email)

    await userEvent.click(menu().getByRole('link', { name: '비밀번호 변경' }))

    expect(router.state.location.pathname).toBe('/password-change')
    expect(router.state.location.state).toEqual({ from: '/me', email: DEMO_USER.email })
  })

  it('내 정보 조회가 실패해도 메뉴는 보이고, 비밀번호 변경은 이메일 없이 넘어간다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.get('/api/v1/users/me', () => fail(403, 'FORBIDDEN', '접근 권한이 없습니다')),
    )
    const { router } = renderRoutes(routes, '/me')

    await screen.findByRole('navigation', { name: '마이페이지 메뉴' })
    // 본문은 오류 안내여도 메뉴는 그대로 있다
    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(menu().getByRole('link', { name: '예매 내역' })).toBeInTheDocument()

    await userEvent.click(menu().getByRole('link', { name: '비밀번호 변경' }))

    expect(router.state.location.state).toEqual({ from: '/me' })
  })
})

describe('MyPageLayout 프로필 카드', () => {
  it('받은 내 정보로 닉네임과 이메일을 보이고, 일반 회원에게는 운영자 표시가 없다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    const profile = within(await screen.findByRole('complementary'))
    expect(await profile.findByText(DEMO_USER.email)).toBeInTheDocument()
    expect(profile.getByText('데모님')).toBeInTheDocument()
    expect(profile.queryByText('운영자')).not.toBeInTheDocument()
  })

  it('운영자에게는 운영자 표시를 보인다', async () => {
    await signInAsAdmin()
    renderRoutes(routes, '/me/reservations')

    const profile = within(await screen.findByRole('complementary'))
    expect(await profile.findByText(ADMIN_USER.email)).toBeInTheDocument()
    expect(profile.getByText('운영자')).toBeInTheDocument()
  })

  it('다른 마이페이지 화면으로 옮겨도 틀은 그대로이고 내 정보는 다시 받지 않는다', async () => {
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/me')
    const profile = within(await screen.findByRole('complementary'))
    await profile.findByText(DEMO_USER.email)

    await act(() => router.navigate('/me/reservations'))

    expect(profile.getByText(DEMO_USER.email)).toBeInTheDocument()
  })
})
