import { describe, expect, it } from 'vitest'
import { resolveQuickLoginAccounts } from '@/auth/quickLogin'

// 운영 빌드에서 빠지는지는 테스트가 아니라 빌드 결과(dist)에서 확인한다(PR 본문)
describe('resolveQuickLoginAccounts', () => {
  it('목 모드에서는 설정이 없어도 목 계정을 쓴다', () => {
    expect(resolveQuickLoginAccounts({ MODE: 'mock' }).map((account) => account.email)).toEqual([
      'demo@iseonjwa.dev',
      'admin@iseonjwa.dev',
    ])
  })

  it('실제 백엔드에 붙인 개발 서버는 .env.local에 이메일과 비밀번호를 다 적은 계정만 보인다', () => {
    const accounts = resolveQuickLoginAccounts({
      MODE: 'development',
      VITE_QUICK_LOGIN_USER_EMAIL: 'me@iseonjwa.dev',
      VITE_QUICK_LOGIN_ADMIN_EMAIL: 'admin@iseonjwa.local',
      VITE_QUICK_LOGIN_ADMIN_PASSWORD: 'secret',
    })

    expect(accounts).toEqual([
      {
        role: 'ADMIN',
        label: '관리자로 로그인',
        email: 'admin@iseonjwa.local',
        password: 'secret',
      },
    ])
  })
})
