import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId } from '@/api/envelope'
import { removeAllEntryTokens } from '@/auth/entryTokens'
import { clearSession, saveTokens, tokenPairSchema } from '@/auth/session'

// 회원·인증 API(SA 11-5, user-service AuthController). 재발급은 `auth/refresh.ts`가 맡는다.

export const authQueryKeys = {
  me: ['users', 'me'],
} as const

export const roleSchema = z.enum(['USER', 'ADMIN'])

export const signupResultSchema = z.object({
  userId: entityId,
  email: z.string(),
  nickname: z.string(),
})

export const meSchema = z.object({
  userId: entityId,
  email: z.string(),
  nickname: z.string(),
  role: roleSchema,
})
export type Me = z.infer<typeof meSchema>

export function signup(body: { email: string; password: string; nickname: string }) {
  return apiRequest({
    method: 'POST',
    path: '/users',
    body,
    schema: signupResultSchema,
    auth: false,
  })
}

export async function login(body: { email: string; password: string }): Promise<void> {
  const tokens = await apiRequest({
    method: 'POST',
    path: '/auth/login',
    body,
    schema: tokenPairSchema,
    auth: false,
  })
  saveTokens(tokens)
}

// 이 탭의 토큰은 항상 지우고, 서버 로그아웃이 실패해도(연결 끊김 등) 던지지 않는다.
// 사용자가 원한 것은 이 브라우저에서 나가는 것이라 화면은 결과와 상관없이 로그인 화면으로 보낸다.
// 서버에 남은 Refresh 토큰은 쓰는 브라우저가 없으므로 수명(14일)이 지나면 사라진다.
export async function logout(): Promise<void> {
  try {
    await apiRequest({ method: 'POST', path: '/auth/logout', schema: z.null() })
  } catch (error) {
    console.warn('[auth] 서버 로그아웃에 실패했습니다. 이 탭의 토큰만 지웁니다', error)
  } finally {
    clearSession()
    removeAllEntryTokens()
  }
}

// 임시 비밀번호로는 로그인할 수 없어(403 PASSWORD_CHANGE_REQUIRED) 로그인 전에 부르는 공개 API다.
export function changePassword(body: {
  email: string
  currentPassword: string
  newPassword: string
}) {
  return apiRequest({
    method: 'POST',
    path: '/auth/password-change',
    body,
    schema: z.null(),
    auth: false,
  })
}

export function getMe() {
  return apiRequest({ method: 'GET', path: '/users/me', schema: meSchema })
}
