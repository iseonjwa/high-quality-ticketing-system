import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { authQueryKeys, getMe, type Me } from '@/api/auth'
import { ME_STALE_MILLIS } from '@/pages/my/MyPageLayout'
import { BillingKeyCard } from '@/components/ticketing/BillingKeyCard'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

const ROLE_LABELS: Record<Me['role'], string> = {
  USER: '일반 회원',
  ADMIN: '운영자',
}

// 마이페이지의 내 정보. 비밀번호 변경·운영 화면 링크는 마이페이지 메뉴(MyPageLayout)에 있다
export function MyInfoPage() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe, staleTime: ME_STALE_MILLIS })

  return (
    <main className="grid min-w-0 content-start gap-6">
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">내 정보</h1>
        <p className="type-body-sm text-gray-500">
          회원 정보와 추첨 자동결제에 쓰는 카드를 관리해요
        </p>
      </div>
      {me.isError ? (
        <ErrorNotice error={me.error} onRetry={() => void me.refetch()} />
      ) : (
        <section aria-labelledby="member-info-title">
          <Card className="gap-5 px-4 py-5 @min-[40rem]:p-6">
            <h2 id="member-info-title" className="type-heading text-gray-900">
              회원 정보
            </h2>
            {me.isPending ? (
              <div className="grid gap-4" role="status">
                <span className="sr-only">내 정보를 불러오고 있어요</span>
                <Skeleton className="h-5 w-3/4" />
                <Skeleton className="h-5 w-1/2" />
                <Skeleton className="h-5 w-1/3" />
              </div>
            ) : (
              <dl className="grid gap-x-6 gap-y-4 type-body @min-[40rem]:grid-cols-2">
                <InfoItem label="이메일">
                  <span className="break-all">{me.data.email}</span>
                </InfoItem>
                <InfoItem label="닉네임">
                  <span className="break-all">{me.data.nickname}</span>
                </InfoItem>
                <InfoItem label="회원 구분">{ROLE_LABELS[me.data.role]}</InfoItem>
                {/* 운영자가 비밀번호를 초기화할 때 회원 번호로 찾는다(회원 검색 API가 없다) */}
                <InfoItem label="회원 번호">
                  <span className="grid gap-0.5">
                    <span className="tabular-nums">{me.data.userId}</span>
                    <span className="type-caption text-gray-500">
                      운영자에게 비밀번호 초기화를 부탁할 때 알려 주세요
                    </span>
                  </span>
                </InfoItem>
              </dl>
            )}
          </Card>
        </section>
      )}
      {me.data ? <BillingKeyCard userId={me.data.userId} /> : null}
    </main>
  )
}

function InfoItem({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid content-start gap-1 rounded-md bg-gray-50 px-4 py-3">
      <dt className="type-caption text-gray-500">{label}</dt>
      <dd className="min-w-0 text-gray-900">{children}</dd>
    </div>
  )
}
