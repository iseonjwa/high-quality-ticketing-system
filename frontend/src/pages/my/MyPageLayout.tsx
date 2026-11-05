import { useQuery } from '@tanstack/react-query'
import { KeyRound, Settings, Ticket, UserRound, type LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { authQueryKeys, getMe, type Me } from '@/api/auth'
import { paths } from '@/app/paths'
import type { AuthNavigationState } from '@/auth/navigation'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

// 마이페이지 틀: 프로필 요약과 메뉴(예매 내역, 내 정보·자동결제 카드, 비밀번호 변경, 운영 화면).
// 넓은 화면은 왼쪽에 붙고, 좁은 화면은 위에서 가로로 넘기는 메뉴가 된다.
// Note: 내 정보 조회(/users/me)는 이 틀이 한 번 부르고 내 정보 화면과 캐시를 나눈다. 헤더에 두면 모든 화면이
// 한 번씩 더 조회해 오픈 순간 부하가 늘어서, 로그인한 사용자가 마이페이지에 왔을 때만 부른다.
export function MyPageLayout() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe })
  const passwordChangeState: AuthNavigationState = { from: paths.me, email: me.data?.email }

  return (
    <div className="grid gap-6 @min-[56rem]:grid-cols-[248px_minmax(0,1fr)] @min-[56rem]:items-start @min-[56rem]:gap-10">
      <aside className="grid min-w-0 gap-4 @min-[56rem]:sticky @min-[56rem]:top-24">
        <ProfileCard me={me.data} pending={me.isPending} />
        <nav aria-label="마이페이지 메뉴" className="min-w-0">
          <ul className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 @min-[40rem]:-mx-6 @min-[40rem]:px-6 @min-[56rem]:mx-0 @min-[56rem]:grid @min-[56rem]:gap-1 @min-[56rem]:overflow-visible @min-[56rem]:px-0">
            <MyNavItem to={paths.myReservations} icon={Ticket}>
              예매 내역
            </MyNavItem>
            <MyNavItem to={paths.me} icon={UserRound} end>
              내 정보·자동결제 카드
            </MyNavItem>
            <MyNavItem to={paths.passwordChange} icon={KeyRound} state={passwordChangeState}>
              비밀번호 변경
            </MyNavItem>
            {/* 운영 화면으로 가는 길은 내 정보 조회가 이미 있는 마이페이지에 둔다 */}
            {me.data?.role === 'ADMIN' ? (
              <MyNavItem to={paths.admin} icon={Settings}>
                운영 화면
              </MyNavItem>
            ) : null}
          </ul>
        </nav>
      </aside>
      <Outlet />
    </div>
  )
}

function ProfileCard({ me, pending }: { me: Me | undefined; pending: boolean }) {
  return (
    <div className="relative hidden overflow-hidden rounded-lg bg-gray-900 p-5 text-white @min-[56rem]:block">
      <span
        aria-hidden="true"
        className="absolute -top-10 -right-10 size-32 rounded-full bg-primary-600/40 blur-2xl"
      />
      {me ? (
        <div className="relative grid gap-3">
          <span
            aria-hidden="true"
            className="inline-flex size-12 items-center justify-center rounded-full bg-primary-600 text-[20px] font-bold"
          >
            {[...me.nickname][0]?.toUpperCase()}
          </span>
          <div className="grid min-w-0 gap-0.5">
            <p className="truncate type-heading">{me.nickname}님</p>
            <p className="truncate type-caption text-white/60">{me.email}</p>
          </div>
          {me.role === 'ADMIN' ? (
            <span className="w-fit rounded-pill bg-white/15 px-2.5 py-0.5 type-caption">
              운영자
            </span>
          ) : null}
        </div>
      ) : pending ? (
        <div className="relative grid gap-3">
          <Skeleton className="size-12 rounded-full bg-white/10" />
          <Skeleton className="h-5 w-2/3 bg-white/10" />
          <Skeleton className="h-4 w-full bg-white/10" />
        </div>
      ) : (
        <p className="relative type-heading">마이페이지</p>
      )}
    </div>
  )
}

function MyNavItem({
  to,
  icon: Icon,
  end,
  state,
  children,
}: {
  to: string
  icon: LucideIcon
  end?: boolean
  state?: AuthNavigationState
  children: string
}) {
  return (
    <li className="shrink-0">
      <NavLink
        to={to}
        end={end}
        state={state}
        className={({ isActive }) =>
          cn(
            'flex h-10 items-center gap-2.5 rounded-md px-3 type-body-sm font-semibold whitespace-nowrap outline-none focus-visible:ring-2 focus-visible:ring-ring',
            '@max-[56rem]:rounded-pill @max-[56rem]:border @max-[56rem]:border-gray-200 @max-[56rem]:bg-gray-0',
            isActive
              ? 'bg-gray-900 text-white @max-[56rem]:border-gray-900 @max-[56rem]:bg-gray-900'
              : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900',
          )
        }
      >
        <Icon aria-hidden="true" className="size-4" />
        {children}
      </NavLink>
    </li>
  )
}
