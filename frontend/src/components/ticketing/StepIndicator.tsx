import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { BOOKING_STEPS } from '@/components/ticketing/bookingSteps'

interface StepIndicatorProps {
  steps?: string[]
  currentIndex: number
}

export function StepIndicator({ steps = BOOKING_STEPS, currentIndex }: StepIndicatorProps) {
  return (
    <ol data-slot="step-indicator" aria-label="예매 단계" className="flex items-center gap-2">
      {steps.map((label, index) => {
        const status =
          index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'upcoming'
        return (
          <li
            key={label}
            data-status={status}
            aria-current={status === 'current' ? 'step' : undefined}
            className={cn(
              'flex items-center gap-2 type-body-sm whitespace-nowrap',
              status === 'current' ? 'font-semibold text-gray-900' : 'text-gray-500',
              index > 0 &&
                'before:mr-2 before:h-px before:w-6 before:bg-gray-200 @max-[60rem]:before:w-3',
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                'grid size-[22px] place-items-center rounded-full type-caption font-semibold',
                status === 'done' && 'bg-primary-50 text-primary-600',
                status === 'current' && 'bg-primary-600 text-gray-0',
                status === 'upcoming' && 'bg-gray-50 text-gray-500',
              )}
            >
              {status === 'done' ? <Check className="size-3.5" strokeWidth={3} /> : index + 1}
            </span>
            {/* 앱 화면이 60rem보다 좁으면 지금 단계 이름만 보인다. 나머지 이름은 화면 낭독기에 남긴다 */}
            <span className={status === 'current' ? undefined : '@max-[60rem]:sr-only'}>
              {label}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
