import { render, screen } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

function classList(value: string): string[] {
  return value.split(' ')
}

describe('cn과 글자 토큰', () => {
  it('type-* 토큰은 글자 색 클래스를 지우지 않는다', () => {
    expect(classList(cn('text-gray-900', 'type-heading'))).toEqual([
      'text-gray-900',
      'type-heading',
    ])
  })

  it('다른 클래스와 합칠 때 글자 색이 남는다', () => {
    const merged = classList(cn('bg-primary text-primary-foreground h-11', 'h-[52px] type-body'))

    expect(merged).toContain('text-primary-foreground')
    expect(merged).toContain('type-body')
    expect(merged).not.toContain('h-11')
  })

  it('뒤에 온 type-*가 앞의 type-*를 이긴다', () => {
    const merged = classList(cn('type-body-sm font-medium', 'type-body'))

    expect(merged).toEqual(['type-body'])
  })

  it('뒤에 온 type-*가 앞의 글자 크기·줄 높이·굵기 클래스를 지운다', () => {
    const merged = classList(cn('text-base leading-snug font-medium text-gray-900', 'type-heading'))

    expect(merged).toEqual(['text-gray-900', 'type-heading'])
  })

  it('type-* 뒤에 온 글자 크기 클래스는 남긴다(뒤의 것이 이긴다)', () => {
    expect(classList(cn('type-heading', 'text-sm'))).toEqual(['type-heading', 'text-sm'])
  })

  it('변형이 붙은 클래스는 건드리지 않는다', () => {
    expect(classList(cn('md:text-sm', 'type-body'))).toEqual(['md:text-sm', 'type-body'])
  })

  it('글자 색끼리는 뒤의 것이 이긴다', () => {
    expect(cn('text-gray-900', 'text-gray-500')).toBe('text-gray-500')
  })
})

describe('Button 글자', () => {
  it('size="lg"는 글자 색을 지키고 16px 클래스 하나만 쓴다', () => {
    render(createElement(Button, { size: 'lg' }, '큰 버튼'))

    const classes = classList(screen.getByRole('button', { name: '큰 버튼' }).className)
    expect(classes).toContain('text-primary-foreground')
    expect(classes).toContain('text-base')
    expect(classes).not.toContain('text-[15px]')
  })
})
