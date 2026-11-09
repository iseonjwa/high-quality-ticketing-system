import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'
import { CircleAlert, CircleCheck, Info, TriangleAlert, X, type LucideIcon } from 'lucide-react'

// 설계 4절: 바탕 -50, 글자·아이콘 -600(경고는 글자 -700). 색에만 기대지 않도록 변형마다 아이콘이 다르다.
// 테두리는 샘플(.alert-warning #FFD8A8, .alert-danger #F8C4CA) 값이다. 샘플에 없는 정보·성공은 글자색을 옅게 쓴다.
const alertVariants = cva(
  '@container/alert relative flex w-full flex-wrap items-start gap-3 rounded-md border px-4 py-3 text-left type-body-sm',
  {
    variants: {
      variant: {
        info: 'border-info-600/20 bg-info-50 text-info-600',
        success: 'border-success-600/20 bg-success-50 text-success-600',
        warning: 'border-[#ffd8a8] bg-warning-50 text-warning-700 dark:border-warning-700/30',
        danger: 'border-[#f8c4ca] bg-danger-50 text-danger-600 dark:border-danger-600/30',
      },
    },
    defaultVariants: { variant: 'info' },
  },
)

type AlertVariant = NonNullable<VariantProps<typeof alertVariants>['variant']>

const ALERT_ICONS: Record<AlertVariant, LucideIcon> = {
  info: Info,
  success: CircleCheck,
  warning: TriangleAlert,
  danger: CircleAlert,
}

// 위험·경고는 바로 읽어 주는 alert, 정보·성공은 흐름을 끊지 않는 status로 알린다.
const ALERT_ROLES: Record<AlertVariant, 'alert' | 'status'> = {
  info: 'status',
  success: 'status',
  warning: 'alert',
  danger: 'alert',
}

function Alert({
  className,
  variant = 'info',
  onClose,
  action,
  children,
  ...props
}: Omit<React.ComponentProps<'div'>, 'role'> &
  VariantProps<typeof alertVariants> & {
    onClose?: () => void
    // 다시 시도처럼 알림 안에서 바로 누를 행동 버튼 자리
    action?: React.ReactNode
  }) {
  const resolved: AlertVariant = variant ?? 'info'
  const Icon = ALERT_ICONS[resolved]

  return (
    <div
      data-slot="alert"
      data-variant={resolved}
      role={ALERT_ROLES[resolved]}
      className={cn(alertVariants({ variant: resolved }), className)}
      {...props}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <div className="min-w-0 flex-1">{children}</div>
      {/* 알림이 좁으면(28rem 미만: 좁은 화면, 옆 열) 버튼을 글자 아래 줄로 내려 글자가 한 줄에 몇 글자씩 접히지 않게 한다 */}
      {action ? (
        <div
          data-slot="alert-action"
          className="shrink-0 @max-[28rem]/alert:order-last @max-[28rem]/alert:basis-full @max-[28rem]/alert:pl-7"
        >
          {action}
        </div>
      ) : null}
      {onClose ? (
        <button
          type="button"
          data-slot="alert-close"
          aria-label="닫기"
          onClick={onClose}
          className="-m-1 shrink-0 rounded-sm p-1 outline-none hover:bg-current/10 focus-visible:ring-2 focus-visible:ring-ring"
        >
          <X className="size-4" aria-hidden="true" />
        </button>
      ) : null}
    </div>
  )
}

function AlertTitle({ className, ...props }: React.ComponentProps<'div'>) {
  return <div data-slot="alert-title" className={cn('font-semibold', className)} {...props} />
}

function AlertDescription({ className, ...props }: React.ComponentProps<'div'>) {
  return <div data-slot="alert-description" className={cn('type-body-sm', className)} {...props} />
}

export { Alert, AlertTitle, AlertDescription }
