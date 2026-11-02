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
              'flex items-center gap-2 type-body-sm',
              status === 'current' ? 'font-semibold text-gray-900' : 'text-gray-500',
              index > 0 && 'before:mr-2 before:h-px before:w-6 before:bg-gray-200',
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                'grid size-[22px] place-items-center rounded-full type-caption font-bold',
                status === 'done' && 'bg-primary-50 text-primary-600',
                status === 'current' && 'bg-primary-600 text-white',
                status === 'upcoming' && 'bg-gray-50 text-gray-500',
              )}
            >
              {status === 'done' ? <Check className="size-3.5" strokeWidth={3} /> : index + 1}
            </span>
            {label}
          </li>
        )
      })}
    </ol>
  )
}
