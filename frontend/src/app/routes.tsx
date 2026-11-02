import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/layout/AppLayout'
import { RouteErrorPage } from '@/components/layout/RouteErrorPage'
import { HomePage } from '@/pages/HomePage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { paths } from '@/app/paths'

// 화면은 ISE-112부터 children에 경로를 더한다. 테스트도 같은 목록으로 라우터를 만든다.
// Note: 자식 화면의 오류는 안쪽 경로의 errorElement가 받아 헤더를 남긴 채 본문만 바꾼다.
// 바깥 errorElement는 레이아웃 자체가 던진 오류용이다. 자식 오류를 바깥에서만 받으면 헤더까지 사라진다.
export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        errorElement: <RouteErrorPage />,
        children: [
          { path: paths.home, element: <HomePage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
