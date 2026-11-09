import { expect } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter, type RouteObject } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { createQueryClient } from '@/api/queryClient'

// 레이아웃이 useQueryClient를 쓰므로 화면 테스트는 이 도우미로 그린다.
export function renderRoutes(routes: RouteObject[], path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = createQueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

// 경로가 lazy라 첫 화면 코드를 받기 전에는 아무것도 그려지지 않는다. 받을 때까지 기다린 뒤 돌려준다.
// 홈·공연 상세처럼 첫 덩어리에 있는 화면은 renderRoutes로 바로 그려도 된다.
// Note: 라우터 상태(initialized)가 먼저 바뀌고 React는 화면을 transition으로 나중에 그린다.
// 느린 CI에서는 그 사이에 요소를 찾다가 실패했으므로 자리표시(RouteLoading)가 사라질 때까지 함께 기다린다.
export async function renderRoutesReady(routes: RouteObject[], path = '/') {
  const rendered = renderRoutes(routes, path)
  await waitFor(() => {
    expect(rendered.router.state.initialized).toBe(true)
    expect(screen.queryByRole('status', { name: '화면을 불러오는 중' })).not.toBeInTheDocument()
  })
  return rendered
}
