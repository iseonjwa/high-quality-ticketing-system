import { expect } from 'vitest'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
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
export async function renderRoutesReady(routes: RouteObject[], path = '/') {
  const rendered = renderRoutes(routes, path)
  await waitFor(() => expect(rendered.router.state.initialized).toBe(true))
  return rendered
}
