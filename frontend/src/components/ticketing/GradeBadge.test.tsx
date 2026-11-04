import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { GradeBadge } from '@/components/ticketing/GradeBadge'

describe('GradeBadge', () => {
  it('일반 등급은 코드를 그대로 보인다', () => {
    render(<GradeBadge grade="VIP" />)

    expect(screen.getByText('VIP')).toHaveAttribute('data-grade', 'VIP')
  })

  it('추첨으로 배정된 좌석(LOTTERY)은 "추첨"으로 보인다', () => {
    render(<GradeBadge grade="LOTTERY" />)

    expect(screen.getByText('추첨')).toHaveAttribute('data-grade', 'LOTTERY')
    expect(screen.queryByText('LOTTERY')).not.toBeInTheDocument()
  })
})
