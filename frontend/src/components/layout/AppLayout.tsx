import { useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { logout } from '@/api/auth'
import { resetUserQueries } from '@/api/queryClient'
import { useBookingStep } from '@/app/bookingStep'
import { useIsBleedLayout } from '@/app/pageLayout'
import { paths } from '@/app/paths'
import { HeaderSlotContext } from '@/components/layout/headerSlotContext'
import { Logo } from '@/components/layout/Logo'
import { SiteFooter } from '@/components/layout/SiteFooter'
import { StepIndicator } from '@/components/ticketing/StepIndicator'
import { Button } from '@/components/ui/button'
import { Toaster } from '@/components/ui/sonner'
import {
  isAuthPath,
  readAuthState,
  toReturnPath,
  type AuthNavigationState,
} from '@/auth/navigation'
import { useIsSignedIn } from '@/auth/session'
import { useIsDemoMode } from '@/demo/demoMode'
import { cn } from '@/lib/utils'

// 시연 모드를 켠 탭에서만 받는다. 켜지 않은 사용자는 패널 코드를 내려받지 않는다
const RequestLogPanel = lazy(() =>
  import('@/demo/RequestLogPanel').then((module) => ({ default: module.RequestLogPanel })),
)

// 앱 화면이 좁을 때(@container 기준 28rem 미만) 메뉴 버튼 여백
const COMPACT_MENU = '@max-md:px-3'

export function AppLayout() {
  const isSignedIn = useIsSignedIn()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [loggingOut, setLoggingOut] = useState(false)
  const demoMode = useIsDemoMode()
  const bookingStep = useBookingStep()
  const bleed = useIsBleedLayout()
  const browsingConcerts =
    location.pathname === paths.home || location.pathname.startsWith('/concerts/')
  // 화면이 HeaderSlot으로 채우는 헤더 자리(좌석 화면의 입장 유효 시간)
  const [headerSlot, setHeaderSlot] = useState<HTMLDivElement | null>(null)

  // logout()은 서버 요청이 실패해도 던지지 않는다. 이전 사용자의 조회 결과는 비우고 공개 조회(공연 목록)는 둔다.
  // clear()는 처음 화면에 붙은 공연 목록까지 캐시와 끊고, 전부 초기화하면 목록이 스켈레톤으로 깜빡인다.
  // 진행 중에는 처리 중으로 둬 두 번 눌러도 요청과 토스트가 한 번만 나가게 한다.
  // Note: 홈으로 먼저 옮긴 뒤 세션을 지운다. 로그인이 필요한 화면에서 세션부터 지우면 그 화면이 로그인 화면으로
  // 보내고(RequireAuth) 다시 홈으로 가서, 뒤로 가기에 로그인 화면이 남는다.
  async function handleLogout() {
    if (loggingOut) {
      return
    }
    setLoggingOut(true)
    try {
      await navigate(paths.home)
      await logout()
      void resetUserQueries(queryClient)
      toast('로그아웃했어요')
    } finally {
      setLoggingOut(false)
    }
  }

  // 헤더의 로그인 링크는 지금 보는 화면을 돌아갈 곳으로 넘긴다. 인증 화면 안에서는 원래 넘겨받은 곳을 그대로 잇는다
  const loginState: AuthNavigationState = isAuthPath(location.pathname)
    ? { from: readAuthState(location.state).from }
    : { from: toReturnPath(location) }

  // 시연 모드 요청 로그 패널은 오른쪽에 고정되고, 그 너비(--demo-panel-width)만큼 앱 화면을 비켜 준다.
  // 패널이 없으면 변수가 없어 여백도 0이다.
  // Note: 앱 화면 안의 반응형은 화면 폭(sm:·md:)이 아니라 이 요소의 너비(@container, 패널을 뺀 앱 화면)로 정한다.
  // 화면 폭은 패널이 앱 화면을 좁힌 것을 몰라서, 창 절반에 패널을 띄우면 좁은 앱 화면에 넓은 배치가 그려진다.
  // 대화상자처럼 화면 전체에 뜨는 것은 화면 폭 그대로 둔다
  return (
    <div className="@container flex min-h-svh flex-col pr-(--demo-panel-width)">
      {/* 메뉴 셋은 앱 화면이 좁을 때(28rem 미만) 버튼 여백을 줄여 360px 휴대폰에도 한 줄로 들어간다 */}
      <header className="sticky top-0 z-20 border-b border-gray-100 bg-gray-0/95 backdrop-blur supports-[backdrop-filter]:bg-gray-0/85">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-4 @min-[40rem]:px-6">
          <div className="flex shrink-0 items-center gap-6">
            {/* 화면마다 h1이 따로 있어서 워드마크는 제목 요소로 만들지 않는다 */}
            <Link
              to={paths.home}
              className="inline-flex shrink-0 rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Logo />
            </Link>
            {/* 예매 흐름 화면에서는 단계 표시에 자리를 내준다. 앱 화면이 40rem보다 좁으면 워드마크가 홈 링크를 대신한다 */}
            {bookingStep === null ? (
              <nav aria-label="둘러보기" className="flex items-center gap-6 @max-[40rem]:hidden">
                {/* 공연 목록과 공연 상세에서 밑줄을 긋는다. 목록에만 aria-current(NavLink)가 붙는다 */}
                <NavLink
                  to={paths.home}
                  end
                  className={cn(
                    'relative rounded-sm py-1 type-body font-semibold outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    browsingConcerts
                      ? 'text-gray-900 after:absolute after:inset-x-0 after:-bottom-4 after:h-0.5 after:bg-gray-900'
                      : 'text-gray-500 hover:text-gray-900',
                  )}
                >
                  공연
                </NavLink>
                <NavLink
                  to={paths.help}
                  className={({ isActive }) =>
                    cn(
                      'relative rounded-sm py-1 type-body font-semibold outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      isActive
                        ? 'text-gray-900 after:absolute after:inset-x-0 after:-bottom-4 after:h-0.5 after:bg-gray-900'
                        : 'text-gray-500 hover:text-gray-900',
                    )
                  }
                >
                  예매 문의
                </NavLink>
              </nav>
            ) : null}
          </div>
          {/* 예매 흐름 화면에서만 가운데에 단계를 보인다. 앱 화면이 48rem보다 좁으면 워드마크·메뉴와 한 줄에 들어가지
              않아 숨긴다(40rem으로 두었을 때 640~673px에서 워드마크가 접혔다). 워드마크와 메뉴는 줄어들지 않는다 */}
          {bookingStep !== null ? (
            <div className="mx-auto px-4 @max-[48rem]:hidden">
              <StepIndicator currentIndex={bookingStep} />
            </div>
          ) : null}
          {/* 비어 있으면 숨긴다. 앱 화면이 62rem보다 좁으면 단계 표시·메뉴와 한 줄에 들어가지 않아(768px에서 46px 넘침,
              단계 이름이 다 보이는 960px에서 오른쪽 여백 22px 잠식) 숨기고, 그 폭에서는 화면이 본문에 같은 내용을 둔다 */}
          <div
            ref={setHeaderSlot}
            className="flex shrink-0 items-center pr-2 empty:hidden @max-[62rem]:hidden"
          />
          <nav aria-label="주 메뉴" className="flex shrink-0 items-center gap-1">
            {isSignedIn ? (
              <>
                <SignedInLink to={paths.myReservations} disabled={loggingOut}>
                  내 예매
                </SignedInLink>
                <SignedInLink to={paths.me} disabled={loggingOut}>
                  내 정보
                </SignedInLink>
                <Button
                  variant="ghost"
                  className={COMPACT_MENU}
                  loading={loggingOut}
                  onClick={() => void handleLogout()}
                >
                  로그아웃
                </Button>
              </>
            ) : (
              <>
                <Button asChild variant="ghost" className={COMPACT_MENU}>
                  <Link to={paths.login} state={loginState}>
                    로그인
                  </Link>
                </Button>
                {/* 좁은 화면에서는 로그인 화면의 회원가입 링크로 간다 */}
                <Button asChild size="sm" className="ml-1 @max-[28rem]:hidden">
                  <Link to={paths.signup} state={loginState}>
                    회원가입
                  </Link>
                </Button>
              </>
            )}
          </nav>
        </div>
      </header>
      {/* 홈·공연 상세는 배너를 화면 끝까지 그리려고 폭을 스스로 정한다(app/pageLayout.ts) */}
      <div
        className={cn(
          'flex w-full flex-1 flex-col',
          !bleed && 'mx-auto max-w-[1200px] px-4 py-8 @min-[40rem]:px-6',
        )}
      >
        <HeaderSlotContext.Provider value={headerSlot}>
          <Outlet />
        </HeaderSlotContext.Provider>
      </div>
      {/* 예매 흐름 화면에서는 지금 단계에 집중하도록 사이트 아래 정보를 두지 않는다 */}
      {bookingStep === null ? <SiteFooter /> : null}
      <Toaster />
      {demoMode && (
        <Suspense fallback={null}>
          <RequestLogPanel />
        </Suspense>
      )}
    </div>
  )
}

// 로그아웃 요청을 기다리는 동안(홈으로 먼저 옮긴 뒤) 로그인한 사용자 메뉴를 누르면 로그아웃이 끝난 뒤
// 그 화면이 로그인 화면으로 보낸다. 그동안은 누름을 막고 흐리게 둔다.
function SignedInLink({
  to,
  disabled,
  children,
}: {
  to: string
  disabled: boolean
  children: ReactNode
}) {
  return (
    <Button
      asChild
      variant="ghost"
      className={cn(COMPACT_MENU, disabled && 'pointer-events-none text-gray-400')}
    >
      <Link
        to={to}
        aria-disabled={disabled ? true : undefined}
        tabIndex={disabled ? -1 : undefined}
        onClick={(event) => {
          if (disabled) {
            event.preventDefault()
          }
        }}
      >
        {children}
      </Link>
    </Button>
  )
}
