import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(<RouterProvider router={router} />)
  return router
}

describe('routes', () => {
  it('첫 화면에 서비스 이름을 보인다', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { name: '이선좌' })).toBeInTheDocument()
  })

  it('없는 주소는 안내를 보이고 처음 화면으로 돌아갈 수 있다', async () => {
    const router = renderAt('/no-such-page')

    expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없어요' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: '처음으로' }))

    expect(router.state.location.pathname).toBe('/')
    expect(screen.getByRole('heading', { name: '이선좌' })).toBeInTheDocument()
  })
})
