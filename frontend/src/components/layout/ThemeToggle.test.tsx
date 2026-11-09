import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThemeToggle } from '@/components/layout/ThemeToggle'
import { readStoredTheme, THEME_COLORS, THEME_STORAGE_KEY } from '@/lib/theme'

// 운영체제 설정(prefers-color-scheme)을 흉내 낸다. set으로 바꾸면 change 이벤트를 보낸다
function mockSystemTheme(dark: boolean) {
  const listeners = new Set<() => void>()
  const query = {
    matches: dark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => query),
  )
  return {
    set(next: boolean) {
      query.matches = next
      act(() => listeners.forEach((listener) => listener()))
    },
  }
}

function currentTheme() {
  return document.documentElement.dataset.theme
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ThemeToggle', () => {
  it('고른 값이 없으면 운영체제 설정을 따른다', () => {
    mockSystemTheme(true)
    render(<ThemeToggle />)

    expect(currentTheme()).toBe('dark')
    expect(screen.getByRole('button', { name: '라이트 모드로 바꾸기' })).toBeInTheDocument()
  })

  it('운영체제 설정을 알 수 없는 브라우저는 라이트로 그린다', () => {
    vi.stubGlobal('matchMedia', undefined)
    render(<ThemeToggle />)

    expect(currentTheme()).toBe('light')
    expect(screen.getByRole('button', { name: '다크 모드로 바꾸기' })).toBeInTheDocument()
  })

  it('누르면 모드를 바꾸고 이 브라우저에 기억한다', async () => {
    mockSystemTheme(false)
    render(<ThemeToggle />)

    await userEvent.click(screen.getByRole('button', { name: '다크 모드로 바꾸기' }))

    expect(currentTheme()).toBe('dark')
    expect(readStoredTheme()).toBe('dark')
    expect(screen.getByRole('button', { name: '라이트 모드로 바꾸기' })).toBeInTheDocument()
  })

  it('기억한 값이 운영체제 설정보다 앞선다', () => {
    mockSystemTheme(true)
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    render(<ThemeToggle />)

    expect(currentTheme()).toBe('light')
  })

  it('고른 값이 없으면 운영체제 설정이 바뀌는 대로 따라간다', () => {
    const system = mockSystemTheme(false)
    render(<ThemeToggle />)

    system.set(true)

    expect(currentTheme()).toBe('dark')
  })

  it('한 번 고르면 운영체제 설정이 바뀌어도 고른 모드를 지킨다', async () => {
    const system = mockSystemTheme(false)
    render(<ThemeToggle />)
    await userEvent.click(screen.getByRole('button', { name: '다크 모드로 바꾸기' }))

    system.set(true)
    system.set(false)

    expect(currentTheme()).toBe('dark')
  })

  it('모드를 바꾸면 휴대폰 주소창 색도 바꾼다', async () => {
    mockSystemTheme(false)
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    document.head.append(meta)
    render(<ThemeToggle />)

    await userEvent.click(screen.getByRole('button', { name: '다크 모드로 바꾸기' }))

    expect(meta.getAttribute('content')).toBe(THEME_COLORS.dark)
    meta.remove()
  })

  it('다른 탭에서 저장소를 비우면 운영체제 설정으로 돌아간다', () => {
    mockSystemTheme(false)
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    render(<ThemeToggle />)
    expect(currentTheme()).toBe('dark')

    localStorage.clear()
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: null }))
    })

    expect(currentTheme()).toBe('light')
  })

  it('다른 탭에서 고른 모드로 맞춘다', () => {
    mockSystemTheme(false)
    render(<ThemeToggle />)

    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY }))
    })

    expect(currentTheme()).toBe('dark')
  })

  it('기억한 값이 알 수 없는 값이면 무시한다', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')

    expect(readStoredTheme()).toBeNull()
  })
})
