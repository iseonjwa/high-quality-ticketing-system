import { describe, expect, it } from 'vitest'
import {
  isEntryTokenFresh,
  readEntryToken,
  removeAllEntryTokens,
  removeEntryToken,
  saveEntryToken,
} from '@/auth/entryTokens'

const NOW = Date.parse('2026-11-02T20:05:00+09:00')

describe('입장 토큰 저장', () => {
  it('회차마다 따로 저장하고 읽는다', () => {
    saveEntryToken(31, { token: 'token-31', expiresAt: '2026-11-02T20:15:00+09:00' })
    saveEntryToken(32, { token: 'token-32', expiresAt: '2026-11-02T20:16:00+09:00' })

    expect(readEntryToken(31)?.token).toBe('token-31')
    expect(readEntryToken(32)?.token).toBe('token-32')
  })

  it('브라우저 시계로 만료돼 보여도 지우지 않는다. 지우는 것은 Gateway의 403뿐이다', () => {
    const entry = { token: 'token-31', expiresAt: '2026-11-02T20:05:00+09:00' }
    saveEntryToken(31, entry)

    expect(isEntryTokenFresh(entry, NOW)).toBe(false)
    expect(readEntryToken(31)?.token).toBe('token-31')
  })

  it('만료 전인지는 대기열을 건너뛸지 정할 때 쓴다', () => {
    const entry = { token: 'token-31', expiresAt: '2026-11-02T20:15:00+09:00' }

    expect(isEntryTokenFresh(entry, NOW)).toBe(true)
  })

  it('읽을 수 없는 값은 지운다', () => {
    sessionStorage.setItem('iseonjwa.entryToken.31', '{not json')

    expect(readEntryToken(31)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.entryToken.31')).toBeNull()
  })

  it('거절된 토큰을 주면 저장한 토큰이 그것일 때만 지운다', () => {
    saveEntryToken(31, { token: 'new-token', expiresAt: '2026-11-02T20:15:00+09:00' })

    removeEntryToken(31, 'old-token')
    expect(readEntryToken(31)?.token).toBe('new-token')

    removeEntryToken(31, 'new-token')
    expect(readEntryToken(31)).toBeNull()
  })

  it('전부 지우기는 입장 토큰만 지우고 다른 값은 남긴다', () => {
    saveEntryToken(31, { token: 'token-31', expiresAt: '2026-11-02T20:15:00+09:00' })
    saveEntryToken(32, { token: 'token-32', expiresAt: '2026-11-02T20:15:00+09:00' })
    sessionStorage.setItem('iseonjwa.session', 'keep')

    removeAllEntryTokens()

    expect(readEntryToken(31)).toBeNull()
    expect(readEntryToken(32)).toBeNull()
    expect(sessionStorage.getItem('iseonjwa.session')).toBe('keep')
  })
})
