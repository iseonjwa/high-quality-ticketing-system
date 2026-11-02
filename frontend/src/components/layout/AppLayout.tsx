import { useQueryClient } from '@tanstack/react-query'
import { Link, Outlet, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { logout } from '@/api/auth'
import { paths } from '@/app/paths'
import { Button } from '@/components/ui/button'
import { Toaster } from '@/components/ui/sonner'
import { useIsSignedIn } from '@/auth/session'

export function AppLayout() {
  const isSignedIn = useIsSignedIn()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  // logout()은 서버 요청이 실패해도 던지지 않는다. 이전 사용자의 조회 결과가 남지 않게 캐시를 비우고 홈으로 보낸다.
  async function handleLogout() {
    await logout()
    queryClient.clear()
    await navigate(paths.home)
    toast('로그아웃했어요')
  }

  return (
    <div className="min-h-svh">
      <header className="border-b border-gray-100 bg-gray-0">
        <div className="mx-auto flex h-16 max-w-[1200px] items-center justify-between px-4 sm:px-6">
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
                <Button asChild variant="ghost">
                  <Link to={paths.myReservations}>내 예매</Link>
                </Button>
                <Button variant="ghost" onClick={() => void handleLogout()}>
                  로그아웃
                </Button>
              </>
            ) : (
              <Button asChild variant="ghost">
                <Link to={paths.login}>로그인</Link>
              </Button>
            )}
          </nav>
        </div>
      </header>
      <div className="mx-auto w-full max-w-[1200px] px-4 py-8 sm:px-6">
        <Outlet />
      </div>
      <Toaster />
    </div>
  )
}
