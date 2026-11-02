import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from 'cn'
import { Slot } from 'radix-ui'

// 설계 4절: 공연 상태 배지(예매 중·오픈 예정·매진·추첨). 등급 배지(VIP·R·S·A)는 티켓팅 컴포넌트 지시에서 더한다.
const badgeVariants = cva(
  'inline-flex h-6 w-fit shrink-0 items-center justify-center gap-1 overflow-hidden rounded-sm border border-transparent px-2 text-caption whitespace-nowrap [&>svg]:pointer-events-none [&>svg]:size-3',
  {
    variants: {
      variant: {
        onSale: 'bg-success-50 text-success-600',
        upcoming: 'bg-info-50 text-info-600',
        soldOut: 'bg-gray-100 text-gray-600',
        lottery: 'bg-primary-50 text-primary-700',
      },
    },
    defaultVariants: { variant: 'onSale' },
  },
)

function Badge({
  className,
  variant = 'onSale',
  asChild = false,
  ...props
}: React.ComponentProps<'span'> & VariantProps<typeof badgeVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : 'span'

  return (
    <Comp
      data-slot="badge"
      data-variant={variant}
      className={cn(badgeVariants({ variant }), className)}
      {...props}
    />
  )
}

export { Badge, badgeVariants }
