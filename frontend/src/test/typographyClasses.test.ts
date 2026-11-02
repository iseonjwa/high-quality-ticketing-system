import { describe, expect, it } from 'vitest'

// `cn`은 `text-body` 같은 사용자 `text-` 토큰을 글자 색으로 보고 함께 쓴 색 클래스를 지운다.
// 글자 토큰은 `type-*`만 쓴다(index.css의 @utility). 옛 이름이 다시 들어오면 여기서 실패한다.
const OLD_TYPOGRAPHY_CLASS = /\btext-(display|title|heading|body-sm|body|caption)\b/

const sources = import.meta.glob('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('글자 토큰 클래스', () => {
  it('소스에 옛 text-* 글자 토큰이 없다', () => {
    const offenders = Object.entries(sources)
      .filter(([path]) => !path.endsWith('/typographyClasses.test.ts'))
      .filter(([, source]) => OLD_TYPOGRAPHY_CLASS.test(source))
      .map(([path]) => path)

    expect(Object.keys(sources).length).toBeGreaterThan(20)
    expect(offenders).toEqual([])
  })
})
