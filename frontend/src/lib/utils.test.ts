import { render, screen } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

describe('cn과 글자 토큰', () => {
  it('type-* 토큰은 글자 색 클래스를 지우지 않는다', () => {
    expect(cn('text-gray-900', 'type-heading')).toBe('text-gray-900 type-heading')
  })

  it('다른 클래스와 합칠 때 text-primary-foreground가 남는다', () => {
    const merged = cn('bg-primary text-primary-foreground h-11', 'h-[52px] type-body')

    expect(merged).toContain('text-primary-foreground')
    expect(merged).toContain('type-body')
    expect(merged).not.toContain('h-11')
  })

  it('글자 색끼리는 뒤의 것이 이긴다', () => {
    expect(cn('text-gray-900', 'text-gray-500')).toBe('text-gray-500')
  })
})

describe('Button', () => {
  it('size="lg"에서도 글자 색이 남는다', () => {
    render(createElement(Button, { size: 'lg' }, '큰 버튼'))

    const classes = screen.getByRole('button', { name: '큰 버튼' }).className
    expect(classes).toContain('text-primary-foreground')
    expect(classes).toContain('type-body')
  })
})
