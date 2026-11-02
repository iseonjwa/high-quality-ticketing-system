import { http } from 'msw'
import { ACCESS_TOKEN_SECONDS, mockDb, mockSettings, nextMockId, type MockUser } from '@/mocks/db'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'

// user-service AuthController와 RedisSessionStore의 동작을 따른다.

function issueTokens(user: MockUser) {
  const sequence = nextMockId()
  const accessToken = `mock-access.${user.userId}.${sequence}`
  const refreshToken = `mock-refresh.${user.userId}.${sequence}`
  mockDb.accessTokens.set(accessToken, user.userId)
  mockDb.refreshTokens.set(user.userId, refreshToken)
  return { accessToken, refreshToken, accessTokenExpiresIn: ACCESS_TOKEN_SECONDS }
}

// Refresh 토큰 주인을 찾는다. 서버처럼 저장된 토큰과 다르면(이미 교체됨) 401 TOKEN_REVOKED다.
function userOfRefreshToken(refreshToken: unknown): MockUser {
  const parts = typeof refreshToken === 'string' ? refreshToken.split('.') : []
  const user = mockDb.users.find((candidate) => candidate.userId === Number(parts[1]))
  if (parts.length !== 3 || parts[0] !== 'mock-refresh' || user === undefined) {
    throw fail(401, 'UNAUTHORIZED', '인증이 필요합니다')
  }

  const stored = mockDb.refreshTokens.get(user.userId)
  const acceptedAfterReload = stored === undefined && mockSettings.acceptUnknownRefreshTokens
  if (stored !== refreshToken && !acceptedAfterReload) {
    throw fail(401, 'TOKEN_REVOKED', '토큰을 사용할 수 없습니다')
  }
  return user
}

export const authHandlers = [
  http.post('/api/v1/users', async ({ request }) => {
    const body = await readBody(request)
    const { email, password, nickname } = body
    if (
      typeof email !== 'string' ||
      !email.includes('@') ||
      typeof password !== 'string' ||
      password.length < 8
    ) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }
    if (mockDb.users.some((user) => user.email === email.toLowerCase())) {
      return fail(409, 'EMAIL_DUPLICATED', '이미 가입한 이메일입니다')
    }
    const user: MockUser = {
      userId: nextMockId(),
      email: email.toLowerCase(),
      password,
      nickname: typeof nickname === 'string' ? nickname : '',
      role: 'USER',
    }
    mockDb.users.push(user)
    return ok({ userId: user.userId, email: user.email, nickname: user.nickname }, { status: 201 })
  }),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const { email, password } = await readBody(request)
    const user = mockDb.users.find(
      (candidate) => candidate.email === email && candidate.password === password,
    )
    if (user === undefined) {
      return fail(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다')
    }
    return ok(issueTokens(user), { message: '로그인되었습니다' })
  }),

  http.post('/api/v1/auth/refresh', async ({ request }) => {
    const { refreshToken } = await readBody(request)
    const user = userOfRefreshToken(refreshToken)
    // 이전 Access 토큰은 만료될 때까지 그대로 쓸 수 있다(서버도 재발급 때 블랙리스트에 넣지 않는다)
    return ok(issueTokens(user), { message: '토큰이 재발급되었습니다' })
  }),

  http.post('/api/v1/auth/logout', ({ request }) => {
    const user = requireUser(request)
    const accessToken = request.headers.get('Authorization')?.slice('Bearer '.length) ?? ''
    mockDb.accessTokens.delete(accessToken)
    mockDb.revokedAccessTokens.add(accessToken)
    mockDb.refreshTokens.delete(user.userId)
    return ok(null, { message: '로그아웃되었습니다' })
  }),

  http.post('/api/v1/auth/password-change', async ({ request }) => {
    const { email, currentPassword, newPassword } = await readBody(request)
    const user = mockDb.users.find(
      (candidate) => candidate.email === email && candidate.password === currentPassword,
    )
    if (user === undefined) {
      return fail(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다')
    }
    if (typeof newPassword !== 'string' || newPassword.length < 8) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
        errors: [{ field: 'newPassword', reason: '8자 이상이어야 합니다' }],
      })
    }
    user.password = newPassword
    return ok(null, { message: '비밀번호가 변경되었습니다' })
  }),

  http.get('/api/v1/users/me', ({ request }) => {
    const user = requireUser(request)
    return ok({ userId: user.userId, email: user.email, nickname: user.nickname, role: user.role })
  }),
]
