import { describe, expect, it } from 'vitest'
import { readAuthState, returnPathOf, toReturnPath } from '@/auth/navigation'

describe('returnPathOf', () => {
  it('앱 안의 경로는 주소 뒤 검색어와 해시까지 그대로 돌려준다', () => {
    expect(returnPathOf({ from: '/me?tab=info#top' })).toBe('/me?tab=info#top')
  })

  it.each([
    ['다른 호스트로 가는 주소', '//evil.example/me'],
    ['역슬래시로 시작하는 다른 호스트 주소', '/\\evil.example'],
    ['절대 주소', 'https://evil.example/me'],
    ['로그인 화면', '/login'],
    ['검색어가 붙은 회원가입 화면', '/signup?from=x'],
    ['비밀번호 변경 화면', '/password-change'],
  ])('%s면 처음 화면으로 보낸다', (_, from) => {
    expect(returnPathOf({ from })).toBe('/')
  })

  it('돌아갈 곳이 없으면 처음 화면이다', () => {
    expect(returnPathOf({})).toBe('/')
  })
})

describe('readAuthState', () => {
  it('모양이 다른 값은 없는 값으로 본다', () => {
    expect(readAuthState(null)).toEqual({})
    expect(readAuthState({ from: 3, email: ['a'], temporaryPassword: 'yes' })).toEqual({
      from: undefined,
      email: undefined,
      temporaryPassword: false,
    })
  })

  it('넘긴 값을 그대로 읽는다', () => {
    expect(readAuthState({ from: '/me', email: 'a@b.dev', temporaryPassword: true })).toEqual({
      from: '/me',
      email: 'a@b.dev',
      temporaryPassword: true,
    })
  })
})

describe('toReturnPath', () => {
  it('경로·검색어·해시를 이어 붙인다', () => {
    expect(toReturnPath({ pathname: '/me', search: '?a=1', hash: '#b' })).toBe('/me?a=1#b')
  })
})
