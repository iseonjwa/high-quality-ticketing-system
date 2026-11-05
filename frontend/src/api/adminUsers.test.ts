import { describe, expect, it } from 'vitest'
import { login } from '@/api/auth'
import { resetUserPassword } from '@/api/adminUsers'
import { ApiError } from '@/api/apiError'
import { sessionStore } from '@/auth/session'
import { mockDb } from '@/mocks/db'
import { DEMO_USER, MAX_LOGIN_FAILURES } from '@/mocks/db'
import { signInAsAdmin, signInAsDemo } from '@/test/api'

// 목이 서버의 비밀번호 초기화(AdminUserController·AuthService.resetPassword, contracts-remaining 6절)와 같은지 확인한다.

const DEMO_USER_ID = 42
// 서버의 임시 비밀번호 문자(혼동하기 쉬운 l, I, O, 0, 1을 뺀 영숫자)
const ALPHABET = /^[abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789]{16}$/

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('비밀번호 초기화 목', () => {
  it('로그인하지 않으면 401이다', async () => {
    const error = await rejectionOf(resetUserPassword(DEMO_USER_ID))

    expect(error.status).toBe(401)
  })

  it('일반 사용자는 403 FORBIDDEN이고 비밀번호는 그대로다', async () => {
    await signInAsDemo()

    const error = await rejectionOf(resetUserPassword(DEMO_USER_ID))

    expect([error.status, error.code]).toEqual([403, 'FORBIDDEN'])
    expect(mockDb.users.find((user) => user.userId === DEMO_USER_ID)?.password).toBe(
      DEMO_USER.password,
    )
  })

  it('없는 회원은 404 NOT_FOUND다', async () => {
    await signInAsAdmin()

    const error = await rejectionOf(resetUserPassword(999_999))

    expect([error.status, error.code]).toEqual([404, 'NOT_FOUND'])
  })

  it('숫자가 아닌 번호는 400 VALIDATION_FAILED다', async () => {
    await signInAsAdmin()
    const token = sessionStore.getState().accessToken as string

    const response = await fetch('/api/v1/admin/users/abc/password-reset', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })

    expect(response.status).toBe(400)
    expect(((await response.json()) as { code: string }).code).toBe('VALIDATION_FAILED')
  })

  it('200 "비밀번호가 초기화되었습니다"와 Cache-Control: no-store, 16자 임시 비밀번호를 준다', async () => {
    await signInAsAdmin()
    const token = sessionStore.getState().accessToken as string

    const response = await fetch(`/api/v1/admin/users/${DEMO_USER_ID}/password-reset`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })

    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    const body = (await response.json()) as { message: string; data: { temporaryPassword: string } }
    expect(body.message).toBe('비밀번호가 초기화되었습니다')
    expect(Object.keys(body.data)).toEqual(['temporaryPassword'])
    expect(body.data.temporaryPassword).toMatch(ALPHABET)
  })

  it('부를 때마다 다른 임시 비밀번호를 만든다', async () => {
    await signInAsAdmin()

    const first = await resetUserPassword(DEMO_USER_ID)
    const second = await resetUserPassword(DEMO_USER_ID)

    expect(first.temporaryPassword).not.toBe(second.temporaryPassword)
  })

  it('이전 비밀번호는 쓸 수 없고 임시 비밀번호로 로그인하면 비밀번호 변경이 필요하다(403)', async () => {
    await signInAsAdmin()
    const { temporaryPassword } = await resetUserPassword(DEMO_USER_ID)

    const old = await rejectionOf(login({ email: DEMO_USER.email, password: DEMO_USER.password }))
    const temporary = await rejectionOf(
      login({ email: DEMO_USER.email, password: temporaryPassword }),
    )

    expect([old.status, old.code]).toEqual([401, 'INVALID_CREDENTIALS'])
    expect([temporary.status, temporary.code]).toEqual([403, 'PASSWORD_CHANGE_REQUIRED'])
    expect(mockDb.users.find((user) => user.userId === DEMO_USER_ID)?.passwordChangeRequired).toBe(
      true,
    )
  })

  it('잠긴 계정의 잠금을 풀고 실패 횟수를 지운다', async () => {
    const user = mockDb.users.find((candidate) => candidate.userId === DEMO_USER_ID)!
    user.locked = true
    user.failedLoginCount = MAX_LOGIN_FAILURES
    await signInAsAdmin()

    const { temporaryPassword } = await resetUserPassword(DEMO_USER_ID)

    expect(user).toMatchObject({ locked: false, failedLoginCount: 0, password: temporaryPassword })
    const error = await rejectionOf(login({ email: DEMO_USER.email, password: temporaryPassword }))
    expect(error.code).toBe('PASSWORD_CHANGE_REQUIRED')
  })

  it('대상의 Refresh 토큰을 폐기한다', async () => {
    await signInAsDemo()
    const refreshToken = sessionStore.getState().refreshToken as string
    expect(mockDb.refreshTokens.get(DEMO_USER_ID)).toBe(refreshToken)
    await signInAsAdmin()

    await resetUserPassword(DEMO_USER_ID)

    expect(mockDb.refreshTokens.has(DEMO_USER_ID)).toBe(false)
    expect(mockDb.revokedRefreshTokens.has(refreshToken)).toBe(true)
  })
})
