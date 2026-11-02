import type { RouteObject } from 'react-router'
import { HomePage } from '@/pages/HomePage'
import { NotFoundPage } from '@/pages/NotFoundPage'

// 화면은 ISE-112부터 이 목록에 경로를 더한다. 테스트도 같은 목록으로 라우터를 만든다.
export const routes: RouteObject[] = [
  { path: '/', element: <HomePage /> },
  { path: '*', element: <NotFoundPage /> },
]
