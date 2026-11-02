import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { createQueryClient } from '@/api/queryClient'
import { routes } from '@/app/routes'
import './index.css'

const queryClient = createQueryClient()
const router = createBrowserRouter(routes)

// `npm run dev:mock`(Vite mode `mock`)에서만 API 목을 켠다. 운영 빌드에서는 조건이 상수 false라
// 목 코드와 워커 파일이 빌드 결과에 들어가지 않는다.
// 워커 등록이 실패해도(예: LAN 주소의 http는 보안 컨텍스트가 아니다) 화면은 띄운다.
if (import.meta.env.MODE === 'mock') {
  try {
    const { startMockApi } = await import('@/mocks/browser')
    await startMockApi()
  } catch (error) {
    console.error('[mock] API 목을 켜지 못했습니다. localhost로 열었는지 확인하세요', error)
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
