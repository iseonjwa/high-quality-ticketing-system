import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { QueuePosition } from '@/components/ticketing/QueuePosition'

// 순번 줄은 "내 순번"과 숫자를 따로 꾸며 글자가 여러 요소로 나뉜다. 알림 영역 하나로 묶여 읽히는지 본다
function liveRegion() {
  const region = screen.getByText('내 순번').closest('[aria-live]')
  if (region === null) {
    throw new Error('순번 알림 영역이 없다')
  }
  return region
}

describe('QueuePosition', () => {
  it('순번과 예상 대기 시간을 문구로 보인다', () => {
    render(<QueuePosition position={15234} estimatedWaitSeconds={300} />)

    expect(liveRegion()).toHaveTextContent('내 순번 15,234번')
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

  it('순번 한 줄에만 aria-live를 둬 예상 시간까지 매번 읽지 않는다', () => {
    render(<QueuePosition position={3} estimatedWaitSeconds={10} />)

    expect(liveRegion()).toHaveTextContent('내 순번 3번')
    expect(liveRegion()).toHaveAttribute('aria-live', 'polite')
    expect(screen.getByText(/예상 대기/).closest('[aria-live]')).toBeNull()
  })
})
