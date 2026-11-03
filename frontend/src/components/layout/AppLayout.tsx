import { useQueryClient } from '@tanstack/react-query'
import { lazy, Suspense, useState, type ReactNode } from 'react'
import { Link, Outlet, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { logout } from '@/api/auth'
import { resetUserQueries } from '@/api/queryClient'
import { paths } from '@/app/paths'
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

// 헤더가 좁을 때(header의 @container 기준 28rem 미만) 메뉴 버튼 여백
const COMPACT_MENU = '@max-md:px-3'

export function AppLayout() {
  const isSignedIn = useIsSignedIn()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [loggingOut, setLoggingOut] = useState(false)
  const demoMode = useIsDemoMode()

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
  // 패널이 없으면 변수가 없어 여백도 0이다
  return (
    <div className="min-h-svh pr-(--demo-panel-width)">
      {/* 여백과 메뉴 크기는 화면이 아니라 헤더 너비로 정한다. 시연 패널이 앱 화면을 좁혀도 워드마크가 줄바꿈되지 않는다.
          메뉴 셋은 좁을 때(28rem 미만) 버튼 여백을 줄여 360px 휴대폰에도 한 줄로 들어간다 */}
      <header className="@container sticky top-0 z-10 border-b border-gray-100 bg-gray-0">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-4 @min-[40rem]:px-6">
          {/* 화면마다 h1이 따로 있어서 워드마크는 제목 요소로 만들지 않는다 */}
          <Link
            to={paths.home}
            className="inline-flex items-center gap-2 rounded-sm text-[20px] font-extrabold tracking-[-0.02em] text-gray-900 outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {/* 꾸밈이라 링크 이름은 글자만 남긴다 */}
            <span
              aria-hidden="true"
              className="relative size-6 rounded-t-[7px] rounded-b-[4px] bg-primary-600"
            >
              <span className="absolute inset-x-[5px] bottom-[5px] h-[5px] rounded-[2px] bg-white" />
            </span>
            이선좌
          </Link>
          <nav aria-label="주 메뉴" className="flex items-center gap-1">
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
              <Button asChild variant="ghost" className={COMPACT_MENU}>
                <Link to={paths.login} state={loginState}>
                  로그인
                </Link>
              </Button>
            )}
          </nav>
        </div>
      </header>
      <div className="mx-auto w-full max-w-[1200px] px-4 py-8 sm:px-6">
        <Outlet />
      </div>
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
