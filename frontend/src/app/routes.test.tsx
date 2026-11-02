import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { renderRoutes } from '@/test/render'

describe('routes', () => {
  it('첫 화면은 공연 목록이다', () => {
    renderRoutes(routes, '/')

    expect(screen.getByRole('heading', { level: 1, name: '공연' })).toBeInTheDocument()
  })

  it('없는 주소는 안내를 보이고 처음 화면으로 돌아갈 수 있다', async () => {
    const { router } = renderRoutes(routes, '/no-such-page')

    expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: '처음으로' }))

    expect(router.state.location.pathname).toBe('/')
    expect(screen.getByRole('heading', { level: 1, name: '공연' })).toBeInTheDocument()
  })
})
