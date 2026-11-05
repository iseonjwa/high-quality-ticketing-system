import { http } from 'msw'
import { mockDb } from '@/mocks/db'
import { requireAdmin } from '@/mocks/handlers/admin'
import { fail, ok } from '@/mocks/respond'

// 운영자 사용자 관리(user-service AdminUserController:28-34, AuthService.resetPassword:99-108).

const TEMPORARY_PASSWORD_LENGTH = 16
// 혼동하기 쉬운 문자(l, I, O, 0, 1)를 뺀 영숫자(AuthService.TEMPORARY_PASSWORD_ALPHABET)
const TEMPORARY_PASSWORD_ALPHABET = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function generateTemporaryPassword(): string {
  const random = crypto.getRandomValues(new Uint32Array(TEMPORARY_PASSWORD_LENGTH))
  return Array.from(
    random,
    (value) => TEMPORARY_PASSWORD_ALPHABET[value % TEMPORARY_PASSWORD_ALPHABET.length],
  ).join('')
}

export const adminUserHandlers = [
  http.post('/api/v1/admin/users/:userId/password-reset', ({ request, params }) => {
    requireAdmin(request)
    const userId = Number(params.userId)
    // 숫자가 아닌 경로 값은 Spring이 400으로 거른다
    if (!Number.isInteger(userId)) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }
    const user = mockDb.users.find((candidate) => candidate.userId === userId)
    if (user === undefined) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }

    // Refresh 토큰을 폐기하고, 임시 비밀번호로 바꾸며 비밀번호 변경 필요로 두고 잠금을 푼다(updatePassword SQL)
    const stored = mockDb.refreshTokens.get(user.userId)
    if (stored !== undefined) {
      mockDb.revokedRefreshTokens.add(stored)
    }
    mockDb.refreshTokens.delete(user.userId)
    const temporaryPassword = generateTemporaryPassword()
    user.password = temporaryPassword
    user.passwordChangeRequired = true
    user.failedLoginCount = 0
    user.locked = false
    return ok(
      { temporaryPassword },
      { message: '비밀번호가 초기화되었습니다', headers: { 'Cache-Control': 'no-store' } },
    )
  }),
]
