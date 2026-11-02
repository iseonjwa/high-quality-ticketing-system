import { http } from 'msw'
import {
  ACCESS_TOKEN_SECONDS,
  MAX_LOGIN_FAILURES,
  mockDb,
  mockSettings,
  newMockUser,
  nextMockId,
  type MockUser,
} from '@/mocks/db'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'

// user-service AuthController·AuthService·RedisSessionStore의 동작을 따른다.

type FieldErrors = { field: string; reason: string }[]

const encoder = new TextEncoder()
// Hibernate @Email처럼 느슨하게 본다(@ 앞뒤에 글자가 있고 공백이 없다)
const EMAIL_FORMAT = /^[^\s@]+@[^\s@]+$/

function text(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function bytes(value: string): number {
  return encoder.encode(value).length
}

// 서버는 저장·조회 전에 앞뒤 공백을 지우고 소문자로 바꾼다(User.normalizeEmail)
function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

// AuthDtos의 Bean Validation. 칸별 오류를 모아 400으로 답한다
function checkEmail(errors: FieldErrors, value: unknown, field = 'email') {
  const email = text(value)
  if (email === null || email.trim() === '' || !EMAIL_FORMAT.test(email) || email.length > 255) {
    errors.push({ field, reason: '올바른 형식의 이메일 주소여야 합니다' })
  }
}

function checkPassword(errors: FieldErrors, value: unknown, field: string, min: number) {
  const password = text(value)
  if (
    password === null ||
    password.trim() === '' ||
    password.length < min ||
    password.length > 72
  ) {
    errors.push({ field, reason: `크기가 ${min}에서 72 사이여야 합니다` })
  }
}

function rejectInvalid(errors: FieldErrors) {
  if (errors.length > 0) {
    throw fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', { errors })
  }
}

function validationFailed() {
  return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
}

// AuthService.authenticate: 비밀번호가 맞으면 사용자를 돌려주고, 틀리면 실패 횟수를 올린다.
// 잠긴 계정은 비밀번호가 맞아도 거절한다. 없는 이메일과 틀린 비밀번호는 같은 응답이다.
function authenticate(email: string, password: string): MockUser {
  if (bytes(password) > 72) {
    throw validationFailed()
  }
  const user = mockDb.users.find((candidate) => candidate.email === normalizeEmail(email))
  if (user === undefined) {
    throw fail(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다')
  }
  rejectLocked(user)
  if (user.password !== password) {
    user.failedLoginCount += 1
    if (user.failedLoginCount >= MAX_LOGIN_FAILURES) {
      user.locked = true
      rejectLocked(user)
    }
    throw fail(401, 'INVALID_CREDENTIALS', '이메일 또는 비밀번호가 올바르지 않습니다')
  }
  return user
}

function rejectLocked(user: MockUser) {
  if (user.locked) {
    throw fail(
      423,
      'ACCOUNT_LOCKED',
      '로그인 시도가 너무 많아 계정이 잠겼습니다. 운영자에게 비밀번호 초기화를 요청하세요',
    )
  }
}

// User.ensureTokenIssuable: 잠긴 계정과 임시 비밀번호 계정에는 토큰을 주지 않는다(로그인·재발급 공통)
function ensureTokenIssuable(user: MockUser) {
  rejectLocked(user)
  if (user.passwordChangeRequired) {
    throw fail(
      403,
      'PASSWORD_CHANGE_REQUIRED',
      '임시 비밀번호입니다. 비밀번호를 변경한 뒤 로그인하세요',
    )
  }
}

function issueTokens(user: MockUser) {
  const sequence = nextMockId()
  const accessToken = `mock-access.${user.userId}.${sequence}`
  const refreshToken = `mock-refresh.${user.userId}.${sequence}`
  mockDb.accessTokens.set(accessToken, user.userId)
  mockDb.refreshTokens.set(user.userId, refreshToken)
  return { accessToken, refreshToken, accessTokenExpiresIn: ACCESS_TOKEN_SECONDS }
}

function revokeRefreshToken(userId: number) {
  const stored = mockDb.refreshTokens.get(userId)
  if (stored !== undefined) {
    mockDb.revokedRefreshTokens.add(stored)
  }
  mockDb.refreshTokens.delete(userId)
}

// Refresh 토큰 주인을 찾는다. 서버처럼 저장된 토큰과 다르면(이미 교체됨) 401 TOKEN_REVOKED다.
function userOfRefreshToken(refreshToken: unknown): MockUser {
  const parts = typeof refreshToken === 'string' ? refreshToken.split('.') : []
  const user = mockDb.users.find((candidate) => candidate.userId === Number(parts[1]))
  if (parts.length !== 3 || parts[0] !== 'mock-refresh' || user === undefined) {
    throw fail(401, 'UNAUTHORIZED', '인증이 필요합니다')
  }

  const stored = mockDb.refreshTokens.get(user.userId)
  const acceptedAfterReload =
    stored === undefined &&
    mockSettings.acceptUnknownRefreshTokens &&
    typeof refreshToken === 'string' &&
    !mockDb.revokedRefreshTokens.has(refreshToken)
  if (stored !== refreshToken && !acceptedAfterReload) {
    throw fail(401, 'TOKEN_REVOKED', '토큰을 사용할 수 없습니다')
  }
  return user
}

export const authHandlers = [
  http.post('/api/v1/users', async ({ request }) => {
    const { email, password, nickname } = await readBody(request)
    const errors: FieldErrors = []
    checkEmail(errors, email)
    checkPassword(errors, password, 'password', 8)
    const name = text(nickname)
    if (name === null || name.trim() === '' || name.length > 30) {
      errors.push({ field: 'nickname', reason: '크기가 0에서 30 사이여야 합니다' })
    }
    rejectInvalid(errors)
    // 검증을 통과했으므로 문자열이다
    const accepted = { email: String(email), password: String(password), nickname: String(name) }
    if (bytes(accepted.password) > 72) {
      return validationFailed()
    }
    if (mockDb.users.some((user) => user.email === normalizeEmail(accepted.email))) {
      return fail(409, 'EMAIL_DUPLICATED', '이미 가입한 이메일입니다')
    }
    const user = newMockUser({
      userId: nextMockId(),
      email: normalizeEmail(accepted.email),
      password: accepted.password,
      nickname: accepted.nickname,
      role: 'USER',
    })
    mockDb.users.push(user)
    return ok(
      { userId: user.userId, email: user.email, nickname: user.nickname },
      { status: 201, message: '가입되었습니다' },
    )
  }),

  http.post('/api/v1/auth/login', async ({ request }) => {
    const { email, password } = await readBody(request)
    const errors: FieldErrors = []
    checkEmail(errors, email)
    if (text(password) === null || String(password).trim() === '' || String(password).length > 72) {
      errors.push({ field: 'password', reason: '공백일 수 없습니다' })
    }
    rejectInvalid(errors)
    const user = authenticate(String(email), String(password))
    ensureTokenIssuable(user)
    user.failedLoginCount = 0
    return ok(issueTokens(user), { message: '로그인되었습니다' })
  }),

  http.post('/api/v1/auth/refresh', async ({ request }) => {
    const { refreshToken } = await readBody(request)
    const user = userOfRefreshToken(refreshToken)
    ensureTokenIssuable(user)
    // 이전 Access 토큰은 만료될 때까지 그대로 쓸 수 있다(서버도 재발급 때 블랙리스트에 넣지 않는다)
    return ok(issueTokens(user), { message: '토큰이 재발급되었습니다' })
  }),

  http.post('/api/v1/auth/logout', ({ request }) => {
    const user = requireUser(request)
    const accessToken = request.headers.get('Authorization')?.slice('Bearer '.length) ?? ''
    mockDb.accessTokens.delete(accessToken)
    mockDb.revokedAccessTokens.add(accessToken)
    revokeRefreshToken(user.userId)
    return ok(null, { message: '로그아웃되었습니다' })
  }),

  // 임시 비밀번호로는 로그인할 수 없어 토큰 없이 현재 비밀번호로 확인한다. 바꾸면 잠금이 풀리고 Refresh 토큰이 폐기된다
  http.post('/api/v1/auth/password-change', async ({ request }) => {
    const { email, currentPassword, newPassword } = await readBody(request)
    const errors: FieldErrors = []
    checkEmail(errors, email)
    if (text(currentPassword) === null || String(currentPassword).trim() === '') {
      errors.push({ field: 'currentPassword', reason: '공백일 수 없습니다' })
    }
    checkPassword(errors, newPassword, 'newPassword', 8)
    rejectInvalid(errors)
    const next = String(newPassword)
    // AuthService.changePassword: 바이트 길이와 같은 비밀번호는 칸 구분 없는 400이다
    if (bytes(next) > 72 || next === currentPassword) {
      return validationFailed()
    }
    const user = authenticate(String(email), String(currentPassword))
    user.password = next
    user.passwordChangeRequired = false
    user.failedLoginCount = 0
    user.locked = false
    revokeRefreshToken(user.userId)
    return ok(null, { message: '비밀번호가 변경되었습니다' })
  }),

  http.get('/api/v1/users/me', ({ request }) => {
    const user = requireUser(request)
    return ok({ userId: user.userId, email: user.email, nickname: user.nickname, role: user.role })
  }),
]
