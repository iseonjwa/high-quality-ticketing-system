import type { ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import { Card, CardContent, CardHeader } from '@/components/ui/card'

interface AuthCardProps {
  title: string
  description?: ReactNode
  children: ReactNode
  // 카드 아래 다른 인증 화면으로 가는 링크 자리
  footer?: ReactNode
}

// 로그인·회원가입·비밀번호 변경이 함께 쓰는 틀. 입력 칸이 길게 늘어나지 않도록 폭을 400px로 묶는다.
export function AuthCard({ title, description, children, footer }: AuthCardProps) {
  return (
    <main className="mx-auto flex w-full max-w-[400px] flex-col gap-5 py-4 sm:py-10">
      <Card className="gap-6 py-7">
        <CardHeader className="gap-1.5">
          <h1 className="type-title text-gray-900">{title}</h1>
          {description ? <p className="type-body-sm text-gray-500">{description}</p> : null}
        </CardHeader>
        <CardContent>{children}</CardContent>
      </Card>
      {footer ? (
        <div className="flex flex-col items-center gap-2 type-body-sm text-gray-500">{footer}</div>
      ) : null}
    </main>
  )
}

// 카드 아래 "아직 회원이 아니에요? 회원가입" 같은 한 줄
export function AuthFooterLine({ children }: { children: ReactNode }) {
  return <p className="flex items-center gap-1.5">{children}</p>
}

export function AuthFooterLink(props: Omit<LinkProps, 'className'>) {
  return (
    <Link
      {...props}
      className="rounded-sm font-semibold text-primary outline-none hover:underline hover:underline-offset-4 focus-visible:ring-2 focus-visible:ring-ring"
    />
  )
}
