import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Input } from '@/components/ui/input'

describe('Input', () => {
  it('aria-invalid가 접근성 트리에 드러난다', () => {
    render(<Input aria-label="이메일" aria-invalid="true" />)

    expect(screen.getByRole('textbox', { name: '이메일' })).toBeInvalid()
  })

  it('비활성이면 입력할 수 없다', () => {
    render(<Input aria-label="이메일" disabled />)

    expect(screen.getByRole('textbox', { name: '이메일' })).toBeDisabled()
  })
})
