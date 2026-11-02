import { afterEach, describe, expect, it } from 'vitest'
import { logout } from '@/api/auth'
import { sessionStore } from '@/auth/session'
import { mockSettings } from '@/mocks/db'
import { signInAsDemo } from '@/test/api'

async function refreshWith(refreshToken: string | null) {
  const response = await fetch('/api/v1/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })
  return { status: response.status, body: (await response.json()) as { code?: string } }
}

describe('목 인증 핸들러', () => {
  afterEach(() => {
    mockSettings.acceptUnknownRefreshTokens = false
  })

  // 브라우저 목 모드는 새로고침 뒤 처음 보는 Refresh 토큰을 받아 준다. 그래도 로그아웃으로 폐기한 토큰은
  // 서버처럼 거절해야 손으로 확인할 때 폐기된 세션이 되살아나 보이지 않는다.
  it('처음 보는 토큰을 받아 주는 설정에서도 로그아웃으로 폐기한 Refresh 토큰은 거절한다', async () => {
    mockSettings.acceptUnknownRefreshTokens = true
    await signInAsDemo()
    const revoked = sessionStore.getState().refreshToken
    await logout()

    const result = await refreshWith(revoked)

    expect(result.status).toBe(401)
    expect(result.body.code).toBe('TOKEN_REVOKED')
  })
})
