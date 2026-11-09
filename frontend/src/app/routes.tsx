import type { ComponentType } from 'react'
import type { RouteObject } from 'react-router'
import { AssistantWidgetProvider } from '@/components/assistant/AssistantWidget'
import { AppLayout } from '@/components/layout/AppLayout'
import { RouteErrorPage } from '@/components/layout/RouteErrorPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ConcertDetailPage } from '@/pages/concerts/ConcertDetailPage'
import { ConcertListPage } from '@/pages/concerts/ConcertListPage'
import { paths } from '@/app/paths'
import { RouteLoading } from '@/app/RouteLoading'
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

type Page = ComponentType<Record<string, never>>

// 같은 흐름의 화면은 routeChunks 묶음 하나에서 받는다. 예매 흐름을 따라 화면을 옮길 때마다 새 파일을 받지 않게 한다.
// 받기에 실패하면 이 경로의 errorElement(RouteErrorPage)가 받는다.
function lazyPage<Module extends Record<string, unknown>>(
  load: () => Promise<Module>,
  name: keyof Module,
): Pick<RouteObject, 'lazy'> {
  return { lazy: async () => ({ Component: (await load())[name] as Page }) }
}

const auth = () => import('@/app/routeChunks/auth')
const booking = () => import('@/app/routeChunks/booking')
const my = () => import('@/app/routeChunks/my')
const admin = () => import('@/app/routeChunks/admin')
const lottery = () => import('@/pages/lotteries/LotteryPage')

// Note: 홈(ConcertListPage)과 공연 상세(ConcertDetailPage)는 첫 방문자가 가장 먼저 여는 화면이라
// 첫 덩어리에 남긴다. 나머지 화면은 경로에 들어갈 때 받는다. 진입점(index)은 이 두 화면과 공용 코드만 담는다.
// Note: 자식 화면의 오류는 안쪽 경로의 errorElement가 받아 헤더를 남긴 채 본문만 바꾼다.
// 바깥 errorElement는 레이아웃 자체가 던진 오류용이다. 자식 오류를 바깥에서만 받으면 헤더까지 사라진다.
export const routes: RouteObject[] = [
  {
    // 예매 문의 위젯 상태(대화·열림)는 레이아웃의 헤더 버튼과 레이아웃 안의 위젯이 같이 쓰도록 레이아웃 바깥에서 든다
    element: (
      <AssistantWidgetProvider>
        <AppLayout />
      </AssistantWidgetProvider>
    ),
    errorElement: <RouteErrorPage />,
    children: [
      {
        errorElement: <RouteErrorPage />,
        hydrateFallbackElement: <RouteLoading />,
        children: [
          { path: paths.home, element: <ConcertListPage />, handle: bleedLayoutHandle },
          { path: paths.concert, element: <ConcertDetailPage />, handle: bleedLayoutHandle },
          { path: paths.login, ...lazyPage(auth, 'LoginPage') },
          { path: paths.signup, ...lazyPage(auth, 'SignupPage') },
          { path: paths.passwordChange, ...lazyPage(auth, 'PasswordChangePage') },
          // 추첨 정보는 공개다. 응모·결과는 화면 안에서 로그인을 안내한다
          { path: paths.lottery, ...lazyPage(lottery, 'LotteryPage') },
          // 로그인이 필요한 화면은 이 아래에 둔다
          {
            element: <RequireAuth />,
            children: [
              // 마이페이지 틀(프로필·메뉴) 안의 화면
              {
                ...lazyPage(my, 'MyPageLayout'),
                children: [
                  { path: paths.me, ...lazyPage(my, 'MyInfoPage') },
                  { path: paths.myReservations, ...lazyPage(my, 'MyReservationsPage') },
                ],
              },
              {
                path: paths.queue,
                ...lazyPage(booking, 'QueuePage'),
                handle: bookingStepHandle(0),
              },
              {
                path: paths.seats,
                ...lazyPage(booking, 'SeatsPage'),
                handle: bookingStepHandle(1),
              },
              {
                path: paths.payment,
                ...lazyPage(booking, 'PaymentPage'),
                handle: bookingStepHandle(2),
              },
              {
                path: paths.reservationComplete,
                lazy: async () => {
                  const { ReservationPage } = await booking()
                  return { element: <ReservationPage variant="complete" /> }
                },
                handle: bookingStepHandle(3),
              },
              {
                path: paths.reservation,
                lazy: async () => {
                  const { ReservationPage } = await booking()
                  return { element: <ReservationPage variant="detail" /> }
                },
              },
              {
                element: <RequireAdmin />,
                children: [
                  { path: paths.admin, ...lazyPage(admin, 'AdminHomePage') },
                  { path: paths.adminConcertNew, ...lazyPage(admin, 'AdminConcertNewPage') },
                  { path: paths.adminConcert, ...lazyPage(admin, 'AdminConcertPage') },
                  { path: paths.adminLotteryNew, ...lazyPage(admin, 'AdminLotteryNewPage') },
                  { path: paths.adminLottery, ...lazyPage(admin, 'AdminLotteryPage') },
                  { path: paths.adminPasswordReset, ...lazyPage(admin, 'AdminPasswordResetPage') },
                  { path: paths.adminDemoFaults, ...lazyPage(admin, 'AdminDemoFaultsPage') },
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
