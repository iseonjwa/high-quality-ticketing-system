import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { THEME_COLORS, THEME_STORAGE_KEY } from '@/lib/theme'
import html from '../../index.html?raw'

// index.html의 첫 그리기 전 스크립트를 그대로 실행해, src/lib/theme.ts와 같은 키·규칙·색인지 확인한다.
// 두 곳이 어긋나면 새로고침 순간 다른 모드로 한 번 그려졌다가 바뀐다.
const inlineScript = /<script>([\s\S]*?)<\/script>/.exec(html)?.[1] ?? ''

function runScript() {
  new Function(inlineScript)()
}

function stubSystemDark(dark: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: dark })),
  )
}

function themeColor() {
  return document.querySelector('meta[name="theme-color"]')?.getAttribute('content')
}

beforeEach(() => {
  const meta = document.createElement('meta')
  meta.name = 'theme-color'
  meta.content = THEME_COLORS.light
  document.head.append(meta)
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.head.querySelector('meta[name="theme-color"]')?.remove()
})

describe('index.html 첫 그리기 전 스크립트', () => {
  it('index.html에 스크립트가 있다', () => {
    expect(inlineScript).toContain('data')
  })

  it('고른 값이 없으면 운영체제 설정을 따르고 주소창 색도 맞춘다', () => {
    stubSystemDark(true)
    runScript()

    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(themeColor()).toBe(THEME_COLORS.dark)
  })

  it('theme.ts와 같은 키에 기억한 값이 운영체제 설정보다 앞선다', () => {
    stubSystemDark(true)
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    runScript()

    expect(document.documentElement.dataset.theme).toBe('light')
    expect(themeColor()).toBe(THEME_COLORS.light)
  })

  it('알 수 없는 값은 무시하고 운영체제 설정을 따른다', () => {
    stubSystemDark(false)
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    runScript()

    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('저장소를 막은 브라우저는 운영체제 설정을 따른다', () => {
    stubSystemDark(true)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('막힘', 'SecurityError')
    })
    runScript()

    expect(document.documentElement.dataset.theme).toBe('dark')
    vi.restoreAllMocks()
  })

  it('운영체제 설정을 알 수 없으면 라이트로 시작한다', () => {
    vi.stubGlobal('matchMedia', undefined)
    runScript()

    expect(document.documentElement.dataset.theme).toBe('light')
  })
})
