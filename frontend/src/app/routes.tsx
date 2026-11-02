import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/layout/AppLayout'
import { RouteErrorPage } from '@/components/layout/RouteErrorPage'
import { HomePage } from '@/pages/HomePage'
import { MyInfoPage } from '@/pages/MyInfoPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { LoginPage } from '@/pages/auth/LoginPage'
import { PasswordChangePage } from '@/pages/auth/PasswordChangePage'
import { SignupPage } from '@/pages/auth/SignupPage'
import { paths } from '@/app/paths'
import { RequireAuth } from '@/app/RequireAuth'

// 테스트도 같은 목록으로 라우터를 만든다.
// 개발 서버에서만 붙는 디자인 시스템 확인 화면. DEV가 빌드 때 상수 false가 돼 운영 번들에는 화면 코드가 들어가지 않는다.
const devRoutes: RouteObject[] = import.meta.env.DEV
  ? [
      {
        path: '/_design',
        lazy: async () => {
          const { DesignSystemPage } = await import('@/pages/DesignSystemPage')
          return { Component: DesignSystemPage }
        },
      },
    ]
  : []

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
          { path: paths.login, element: <LoginPage /> },
          { path: paths.signup, element: <SignupPage /> },
          { path: paths.passwordChange, element: <PasswordChangePage /> },
          // 로그인이 필요한 화면은 이 아래에 둔다
          { element: <RequireAuth />, children: [{ path: paths.me, element: <MyInfoPage /> }] },
          ...devRoutes,
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
