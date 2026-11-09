import { useCallback, useEffect, useLayoutEffect, useState } from 'react'

// 화면 모드(design-system.md 2-5절). 처음에는 운영체제 설정을 따르고, 헤더 버튼으로 고른 값은 이 브라우저에 기억한다.
// Note: index.html의 첫 그리기 전 스크립트가 같은 키와 규칙으로 <html data-theme>를 먼저 정한다. 이 파일만 바꾸면
// 새로고침 순간 다른 모드로 한 번 그려졌다가 바뀐다. 키나 규칙을 바꾸면 두 곳을 함께 바꾼다.
export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'iseonjwa:theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export function readStoredTheme(): Theme | null {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    // 사생활 보호 모드처럼 저장소를 막은 브라우저에서는 기억하지 않고 운영체제 설정을 따른다
    return null
  }
}

function storeTheme(theme: Theme) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // 기억하지 못해도 이번 화면에는 적용한다
  }
}

function darkQuery(): MediaQueryList | null {
  return typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null
}

export function systemTheme(): Theme {
  return darkQuery()?.matches ? 'dark' : 'light'
}

// 휴대폰 주소창 색(meta theme-color). 페이지 바탕(gray-25)과 같다
export const THEME_COLORS: Record<Theme, string> = { light: '#fbfbfd', dark: '#0c0d13' }

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme])
}

// 지금 모드와 바꾸는 함수. 고른 값이 없으면 운영체제 설정이 바뀌는 대로 따라간다.
// 다른 탭에서 고른 값도 storage 이벤트로 받아 같은 모드로 맞춘다.
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [stored, setStored] = useState(readStoredTheme)
  const [system, setSystem] = useState(systemTheme)
  const theme = stored ?? system

  useEffect(() => {
    const query = darkQuery()
    if (query === null) {
      return
    }
    const onChange = () => setSystem(query.matches ? 'dark' : 'light')
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      // 다른 탭에서 저장소를 통째로 비우면 key가 null로 온다
      if (event.key === THEME_STORAGE_KEY || event.key === null) {
        setStored(readStoredTheme())
      }
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  // 그리기 전에 바꿔 버튼 아이콘과 화면 색이 한 번에 바뀌게 한다
  useLayoutEffect(() => {
    applyTheme(theme)
  }, [theme])

  const toggle = useCallback(() => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    storeTheme(next)
    setStored(next)
  }, [theme])

  return { theme, toggle }
}
