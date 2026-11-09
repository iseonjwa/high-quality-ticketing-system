import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/layout/AppLayout'
import { RouteErrorPage } from '@/components/layout/RouteErrorPage'
import { MyInfoPage } from '@/pages/MyInfoPage'
import { MyPageLayout } from '@/pages/my/MyPageLayout'
import { AssistantPage } from '@/pages/assistant/AssistantPage'
import { AdminConcertNewPage } from '@/pages/admin/AdminConcertNewPage'
import { AdminConcertPage } from '@/pages/admin/AdminConcertPage'
import { AdminDemoFaultsPage } from '@/pages/admin/AdminDemoFaultsPage'
import { AdminHomePage } from '@/pages/admin/AdminHomePage'
import { AdminLotteryNewPage } from '@/pages/admin/AdminLotteryNewPage'
import { AdminLotteryPage } from '@/pages/admin/AdminLotteryPage'
import { AdminPasswordResetPage } from '@/pages/admin/AdminPasswordResetPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { LoginPage } from '@/pages/auth/LoginPage'
import { ConcertDetailPage } from '@/pages/concerts/ConcertDetailPage'
import { ConcertListPage } from '@/pages/concerts/ConcertListPage'
import { PasswordChangePage } from '@/pages/auth/PasswordChangePage'
import { QueuePage } from '@/pages/queue/QueuePage'
import { SeatsPage } from '@/pages/seats/SeatsPage'
import { PaymentPage } from '@/pages/payment/PaymentPage'
import { LotteryPage } from '@/pages/lotteries/LotteryPage'
import { MyReservationsPage } from '@/pages/reservations/MyReservationsPage'
import { ReservationPage } from '@/pages/reservations/ReservationPage'
import { SignupPage } from '@/pages/auth/SignupPage'
import { paths } from '@/app/paths'
import { RequireAdmin } from '@/app/RequireAdmin'
import { RequireAuth } from '@/app/RequireAuth'
import { bookingStepHandle } from '@/app/bookingStep'
import { bleedLayoutHandle } from '@/app/pageLayout'

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
          { path: paths.home, element: <ConcertListPage />, handle: bleedLayoutHandle },
          { path: paths.concert, element: <ConcertDetailPage />, handle: bleedLayoutHandle },
          { path: paths.login, element: <LoginPage /> },
          { path: paths.signup, element: <SignupPage /> },
          { path: paths.passwordChange, element: <PasswordChangePage /> },
          // 추첨 정보는 공개다. 응모·결과는 화면 안에서 로그인을 안내한다
          { path: paths.lottery, element: <LotteryPage /> },
          // 로그인이 필요한 화면은 이 아래에 둔다
          {
            element: <RequireAuth />,
            children: [
              // 마이페이지 틀(프로필·메뉴) 안의 화면
              {
                element: <MyPageLayout />,
                children: [
                  { path: paths.me, element: <MyInfoPage /> },
                  { path: paths.myReservations, element: <MyReservationsPage /> },
                ],
              },
              { path: paths.queue, element: <QueuePage />, handle: bookingStepHandle(0) },
              { path: paths.seats, element: <SeatsPage />, handle: bookingStepHandle(1) },
              { path: paths.payment, element: <PaymentPage />, handle: bookingStepHandle(2) },
              {
                path: paths.reservationComplete,
                element: <ReservationPage variant="complete" />,
                handle: bookingStepHandle(3),
              },
              { path: paths.reservation, element: <ReservationPage variant="detail" /> },
              { path: paths.help, element: <AssistantPage /> },
              {
                element: <RequireAdmin />,
                children: [
                  { path: paths.admin, element: <AdminHomePage /> },
                  { path: paths.adminConcertNew, element: <AdminConcertNewPage /> },
                  { path: paths.adminConcert, element: <AdminConcertPage /> },
                  { path: paths.adminLotteryNew, element: <AdminLotteryNewPage /> },
                  { path: paths.adminLottery, element: <AdminLotteryPage /> },
                  { path: paths.adminPasswordReset, element: <AdminPasswordResetPage /> },
                  { path: paths.adminDemoFaults, element: <AdminDemoFaultsPage /> },
                ],
              },
            ],
          },
          ...devRoutes,
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
