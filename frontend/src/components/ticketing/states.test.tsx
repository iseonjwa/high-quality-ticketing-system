import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/apiError'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { PendingStatus } from '@/components/ticketing/PendingStatus'
import { BOOKING_STEPS } from '@/components/ticketing/bookingSteps'
import { StepIndicator } from '@/components/ticketing/StepIndicator'

describe('PendingStatus', () => {
  it('status 역할로 제목과 설명을 알린다', () => {
    render(<PendingStatus title="결제를 확인하고 있어요" description="잠시만 기다려 주세요" />)

    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('결제를 확인하고 있어요')
    expect(status).toHaveTextContent('잠시만 기다려 주세요')
  })
})

describe('StepIndicator', () => {
  it('현재 단계에만 aria-current="step"을 두고 완료·예정을 나눈다', () => {
    render(<StepIndicator steps={BOOKING_STEPS} currentIndex={1} />)

    const items = screen.getAllByRole('listitem')
    expect(items.map((i) => i.getAttribute('data-status'))).toEqual([
      'done',
      'current',
      'upcoming',
      'upcoming',
    ])
    expect(screen.getByText('좌석 선택').closest('li')).toHaveAttribute('aria-current', 'step')
    expect(screen.getByText('대기열').closest('li')).not.toHaveAttribute('aria-current')
  })

  it('단계를 주지 않으면 예매 흐름 기본값을 쓴다', () => {
    render(<StepIndicator currentIndex={0} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(BOOKING_STEPS.length)
  })
})

describe('EmptyState', () => {
  it('제목·설명·행동 버튼을 보인다', async () => {
    const onClick = vi.fn()
    render(
      <EmptyState
        title="아직 예매한 공연이 없어요"
        description="공연 목록에서 골라 보세요"
        action={<button onClick={onClick}>공연 보러 가기</button>}
      />,
    )

    expect(screen.getByText('아직 예매한 공연이 없어요')).toBeInTheDocument()
    expect(screen.getByText('공연 목록에서 골라 보세요')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '공연 보러 가기' }))

    expect(onClick).toHaveBeenCalledTimes(1)
  })
})

describe('ErrorNotice', () => {
  it('SEAT_ALREADY_HELD는 경고 문구로 보인다', () => {
    const error = new ApiError({ status: 409, code: 'SEAT_ALREADY_HELD', message: '' })
    render(<ErrorNotice error={error} />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveAttribute('data-variant', 'warning')
    expect(alert).toHaveTextContent('이미 선택된 좌석입니다. 남은 좌석은 그대로 두었어요')
    expect(alert).not.toHaveTextContent('문의 번호')
  })

  it('모르는 코드는 위험 문구와 문의 번호로 보인다', () => {
    const error = new ApiError({
      status: 500,
      code: 'SOMETHING_NEW',
      message: '',
      traceId: 'trace-xyz-1',
    })
    render(<ErrorNotice error={error} />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveAttribute('data-variant', 'danger')
    expect(alert).toHaveTextContent('문의 번호 trace-xyz-1')
  })

  it('onRetry가 있으면 다시 시도 버튼이 불린다', async () => {
    const onRetry = vi.fn()
    render(<ErrorNotice error={new Error('x')} onRetry={onRetry} />)

    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('onRetry가 없으면 다시 시도 버튼이 없다', () => {
    render(<ErrorNotice error={new Error('x')} />)

    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })
})
