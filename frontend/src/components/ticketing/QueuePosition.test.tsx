import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { QueuePosition } from '@/components/ticketing/QueuePosition'

describe('QueuePosition', () => {
  it('순번과 예상 대기 시간을 문구로 보인다', () => {
    render(<QueuePosition position={15234} estimatedWaitSeconds={300} />)

    expect(screen.getByText('내 앞에 15,234명')).toBeInTheDocument()
    expect(screen.getByText(/예상 대기/)).toHaveTextContent('예상 대기 약 5분')
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('처음 순번이 있으면 진행 막대를 보인다', () => {
    render(<QueuePosition position={250} estimatedWaitSeconds={60} initialPosition={1000} />)

    expect(screen.getByRole('progressbar', { name: '대기 진행률' })).toHaveAttribute(
      'aria-valuenow',
      '75',
    )
  })

  it('순번이 처음보다 커져도 막대 값은 0~100 안이다', () => {
    render(<QueuePosition position={2000} estimatedWaitSeconds={60} initialPosition={1000} />)

    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
  })

  it('순번이 바뀌면 읽도록 aria-live를 둔다', () => {
    render(<QueuePosition position={3} estimatedWaitSeconds={10} />)

    expect(screen.getByText('내 앞에 3명').parentElement).toHaveAttribute('aria-live', 'polite')
  })
})
