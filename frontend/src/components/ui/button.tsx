import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from 'cn'
import { Loader2 } from 'lucide-react'
import { Slot } from 'radix-ui'

// 설계 4절: 주(default)·보조(outline)·위험(destructive), 크기 기본 44·큰 52.
// ghost·link는 헤더처럼 버튼 모양이 부담스러운 곳에 쓴다.
const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center gap-2 rounded-md border border-transparent bg-clip-padding type-body-sm font-medium whitespace-nowrap transition-colors duration-(--dur-fast) ease-out outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 active:translate-y-px disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:bg-primary-700',
        outline: 'border-input bg-gray-0 text-gray-900 hover:bg-gray-50',
        destructive: 'bg-danger-600 text-white hover:bg-danger-600/90',
        ghost: 'text-gray-700 hover:bg-gray-50 hover:text-gray-900',
        link: 'text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-11 px-5',
        sm: 'h-9 px-3',
        lg: 'h-[52px] px-6 type-body',
        icon: 'size-11',
        'icon-sm': 'size-9',
        'icon-lg': 'size-[52px]',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)

function Button({
  className,
  variant = 'default',
  size = 'default',
  asChild = false,
  loading = false,
  disabled,
  children,
  ...props
}: React.ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
    // 처리 중이면 스피너를 앞에 보이고 누를 수 없게 한다. 글자는 그대로 둬 무엇을 처리 중인지 남긴다.
    loading?: boolean
  }) {
  const Comp = asChild ? Slot.Root : 'button'

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      data-loading={loading ? '' : undefined}
      className={cn(buttonVariants({ variant, size, className }))}
      disabled={disabled || loading}
      aria-busy={loading ? true : undefined}
      {...props}
    >
      {/* Slot은 자식 하나만 받으므로 asChild에서는 스피너 없이 자식만 넘긴다 */}
      {asChild ? (
        children
      ) : (
        <>
          {loading ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          {children}
        </>
      )}
    </Comp>
  )
}

export { Button, buttonVariants }
