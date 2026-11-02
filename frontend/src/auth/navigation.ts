import type { Location } from 'react-router'
import { paths } from '@/app/paths'

// 로그인이 필요한 화면에서 로그인 화면으로 보낼 때, 인증 화면끼리 오갈 때 history state로 넘기는 값.
export interface AuthNavigationState {
  // 로그인한 뒤 돌아갈 경로(pathname + search + hash)
  from?: string
  // 다음 화면에 미리 채울 이메일
  email?: string
  // 임시 비밀번호로 로그인하려다 비밀번호 변경으로 왔다(403 PASSWORD_CHANGE_REQUIRED)
  temporaryPassword?: boolean
}

const AUTH_PATHS: readonly string[] = [paths.login, paths.signup, paths.passwordChange]

export function isAuthPath(pathname: string): boolean {
  return AUTH_PATHS.includes(pathname)
}

export function toReturnPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  return `${location.pathname}${location.search}${location.hash}`
}

// history state는 브라우저에 남아 새로고침·뒤로 가기에도 돌아온다. 모양이 다르면 없는 값으로 본다.
export function readAuthState(state: unknown): AuthNavigationState {
  if (typeof state !== 'object' || state === null) {
    return {}
  }
  const { from, email, temporaryPassword } = state as Record<string, unknown>
  return {
    from: typeof from === 'string' ? from : undefined,
    email: typeof email === 'string' ? email : undefined,
    temporaryPassword: temporaryPassword === true,
  }
}

// 이 앱 안의 경로만 돌아갈 곳으로 받는다. `//다른-호스트`는 다른 사이트로 가는 주소라 막고,
// 인증 화면으로 돌아가면 로그인한 뒤 같은 화면을 다시 보게 되므로 처음 화면으로 보낸다.
export function returnPathOf(state: AuthNavigationState): string {
  const { from } = state
  if (
    from === undefined ||
    !from.startsWith('/') ||
    from.startsWith('//') ||
    from.startsWith('/\\')
  ) {
    return paths.home
  }
  const pathname = from.split(/[?#]/, 1)[0]
  return isAuthPath(pathname) ? paths.home : from
}
