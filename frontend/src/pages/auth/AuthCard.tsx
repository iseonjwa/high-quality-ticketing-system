import { Armchair, ListOrdered, Ticket } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import { Logo } from '@/components/layout/Logo'
import { concertArt, POSTER_SIZE } from '@/components/ticketing/concertArt'

interface AuthCardProps {
  title: string
  description?: ReactNode
  children: ReactNode
  // 카드 아래 다른 인증 화면으로 가는 링크 자리
  footer?: ReactNode
}

const FEATURES = [
  { icon: ListOrdered, text: '대기열로 들어온 순서를 지켜요' },
  { icon: Armchair, text: '고른 좌석은 결제하는 동안 잡아 둬요' },
  { icon: Ticket, text: '일부 좌석은 추첨으로 공정하게 나눠요' },
] as const

// 로그인·회원가입·비밀번호 변경이 함께 쓰는 틀. 넓은 화면에서는 왼쪽에 공연 그림과 서비스 소개를 두고
// 오른쪽에 입력 폼을 둔다. 입력 칸이 길게 늘어나지 않도록 폼 폭은 400px로 묶는다.
// Note: 그림 패널은 좁은 화면에서 숨긴다. loading="lazy"라 숨긴 동안에는 그림을 받지 않는다.
export function AuthCard({ title, description, children, footer }: AuthCardProps) {
  const art = concertArt(1)
  return (
    <main className="mx-auto grid w-full max-w-[960px] overflow-hidden rounded-lg @min-[40rem]:my-6 @min-[56rem]:grid-cols-[1fr_1fr] @min-[56rem]:border @min-[56rem]:border-gray-100 @min-[56rem]:bg-gray-0 @min-[56rem]:shadow-md">
      <div className="relative hidden min-h-[560px] overflow-hidden bg-gray-900 text-white @min-[56rem]:block">
        <img
          src={art.poster}
          alt=""
          width={POSTER_SIZE.width}
          height={POSTER_SIZE.height}
          loading="lazy"
          decoding="async"
          className="absolute inset-0 size-full object-cover"
        />
        <div className="absolute inset-0 bg-linear-to-t from-gray-900 via-gray-900/60 to-gray-900/10" />
        <div className="relative flex h-full flex-col justify-between p-8">
          <Logo tone="light" />
          <div className="grid gap-5">
            <p className="text-[28px] leading-[1.25] font-extrabold tracking-[-0.03em] break-keep">
              오픈 순간에도
              <br />
              흔들리지 않는 예매
            </p>
            <ul className="grid gap-2.5 type-body-sm text-white/85">
              {FEATURES.map(({ icon: Icon, text }) => (
                <li key={text} className="flex items-center gap-2.5">
                  <span className="inline-flex size-7 items-center justify-center rounded-full bg-white/10">
                    <Icon aria-hidden="true" className="size-4" />
                  </span>
                  {text}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
      <div className="grid content-center justify-items-center py-4 @min-[40rem]:py-6 @min-[56rem]:px-10 @min-[56rem]:py-12">
        <div className="grid w-full max-w-[400px] gap-5">
          <div className="grid gap-6 rounded-lg border border-gray-100 bg-gray-0 px-6 py-7 shadow-sm @min-[56rem]:rounded-none @min-[56rem]:border-0 @min-[56rem]:p-0 @min-[56rem]:shadow-none">
            <div className="grid gap-1.5">
              <h1 className="type-title text-gray-900">{title}</h1>
              {description ? <p className="type-body-sm text-gray-500">{description}</p> : null}
            </div>
            <div>{children}</div>
          </div>
          {footer ? (
            <div className="flex flex-col items-center gap-2 type-body-sm text-gray-500">
              {footer}
            </div>
          ) : null}
        </div>
      </div>
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
