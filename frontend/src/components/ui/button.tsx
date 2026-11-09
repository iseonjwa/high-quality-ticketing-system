import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'
import { Loader2 } from 'lucide-react'
import { Slot } from 'radix-ui'

// 설계 4절: 주(default)·보조(outline)·위험(destructive), 크기 기본 44·큰 52.
// ghost·link는 헤더처럼 버튼 모양이 부담스러운 곳에 쓴다.
// 글자와 비활성 모양은 샘플(.btn: 15px·600, 큰 버튼 16px, 비활성 gray-200 바탕·gray-400 글자)을 따른다.
const buttonVariants = cva(
  "group/button inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md border border-transparent bg-clip-padding font-semibold whitespace-nowrap transition-colors duration-(--dur-fast) ease-out outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-gray-0 active:translate-y-px disabled:cursor-not-allowed disabled:active:translate-y-0 aria-busy:cursor-wait aria-busy:active:translate-y-0 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default:
          'bg-primary text-primary-foreground hover:bg-primary-700 disabled:bg-gray-200 disabled:text-gray-400',
        outline:
          'border-gray-200 bg-gray-0 text-gray-900 hover:bg-gray-50 disabled:bg-gray-0 disabled:text-gray-400',
        destructive:
          'bg-danger-600 text-gray-0 hover:bg-danger-600/90 disabled:bg-gray-200 disabled:text-gray-400',
        ghost: 'text-gray-700 hover:bg-gray-50 hover:text-gray-900 disabled:text-gray-400',
        link: 'text-primary-600 underline-offset-4 hover:underline disabled:text-gray-400',
      },
      size: {
        default: 'h-11 px-5 text-[15px] leading-[22px]',
        sm: 'h-9 px-3 text-sm',
        // 큰 행동 버튼은 알약 모양이다(design-system.md 12절, 시안 B)
        lg: 'h-[52px] rounded-full px-6 text-base',
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
  onClick,
  children,
  ...props
}: React.ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
    // 처리 중이면 스피너를 앞에 보이고 누를 수 없게 한다. 글자는 그대로 둬 무엇을 처리 중인지 남긴다.
    loading?: boolean
  }) {
  const Comp = asChild ? Slot.Root : 'button'

  // Note: 처리 중에는 disabled 대신 aria-disabled를 쓴다. disabled는 초점을 잃게 해서, 키보드로 "선택 완료"를 누른
  // 사용자의 초점이 페이지 맨 앞으로 튄다. 대신 누름을 막아 같은 요청이 두 번 나가지 않게 한다.
  function handleClick(event: React.MouseEvent<HTMLButtonElement>) {
    if (loading) {
      event.preventDefault()
      return
    }
    onClick?.(event)
  }

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      data-loading={loading ? '' : undefined}
      className={cn(buttonVariants({ variant, size, className }))}
      disabled={disabled}
      aria-disabled={loading ? true : undefined}
      aria-busy={loading ? true : undefined}
      onClick={handleClick}
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
