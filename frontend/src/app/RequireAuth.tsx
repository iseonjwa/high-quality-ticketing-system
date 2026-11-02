import { Navigate, Outlet, useLocation } from 'react-router'
import { paths } from '@/app/paths'
import { toReturnPath, type AuthNavigationState } from '@/auth/navigation'
import { useIsSignedIn } from '@/auth/session'

// 로그인이 필요한 화면을 감싸는 경로. 로그인 전이면 지금 주소를 돌아갈 곳으로 넘기고 로그인 화면으로 보낸다.
// 보던 중에 재발급이 거절돼 세션이 끝나도 로그인 여부가 바뀌면서 같은 길로 간다(API 클라이언트 규칙 401).
export function RequireAuth() {
  const isSignedIn = useIsSignedIn()
  const location = useLocation()

  if (!isSignedIn) {
    const state: AuthNavigationState = { from: toReturnPath(location) }
    return <Navigate to={paths.login} replace state={state} />
  }
  return <Outlet />
}
