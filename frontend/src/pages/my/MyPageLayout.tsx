import { useQuery } from '@tanstack/react-query'
import { KeyRound, Settings, Ticket, UserRound, type LucideIcon } from 'lucide-react'
import type { FocusEvent } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router'
import { authQueryKeys, getMe, type Me } from '@/api/auth'
import { paths } from '@/app/paths'
import { toReturnPath, type AuthNavigationState } from '@/auth/navigation'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

// 내 정보는 5분 동안 다시 받지 않는다(이름·이메일·역할은 거의 바뀌지 않는다)
export const ME_STALE_MILLIS = 5 * 60 * 1000

// 가로로 넘기는 메뉴에서 키보드 초점이 간 항목이 반쯤 가려져 있으면 보이게 넘긴다.
// Chrome은 일부라도 보이는 요소면 초점만으로 넘기지 않는다(셀프 리뷰, 360px)
function revealInScroller(event: FocusEvent<HTMLAnchorElement>) {
  event.currentTarget.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
}

// 마이페이지 틀: 프로필 요약과 메뉴(예매 내역, 내 정보·자동결제 카드, 비밀번호 변경, 운영 화면).
// 넓은 화면은 왼쪽에 붙고, 좁은 화면은 위에서 가로로 넘기는 메뉴가 된다.
// Note: 내 정보 조회(/users/me)는 로그인한 사용자가 마이페이지에 왔을 때만 부른다. 헤더에 두면 모든 화면이
// 한 번씩 더 조회해 오픈 순간 부하가 는다. 내 정보 화면과 같은 키·같은 신선도(ME_STALE_MILLIS)라 메뉴로 오가도
// 다시 받지 않는다. 로그아웃·로그인·비밀번호 변경은 캐시를 비우므로 지난 사용자 정보가 남지 않는다.
export function MyPageLayout() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe, staleTime: ME_STALE_MILLIS })
  const location = useLocation()
  // 비밀번호를 바꾸거나 그만두면 메뉴를 누른 화면(내 예매·내 정보)으로 돌아온다
  const passwordChangeState: AuthNavigationState = {
    from: toReturnPath(location),
    email: me.data?.email,
  }

  return (
    <div className="grid gap-6 @min-[56rem]:grid-cols-[248px_minmax(0,1fr)] @min-[56rem]:items-start @min-[56rem]:gap-10">
      {/* 메뉴는 이 화면들의 주 이동 수단이라 보조 내용(aside)으로 감싸지 않는다 */}
      <div className="grid min-w-0 content-start gap-4 @min-[56rem]:sticky @min-[56rem]:top-24">
        <ProfileCard me={me.data} pending={me.isPending} />
        <nav aria-label="마이페이지 메뉴" className="min-w-0">
          {/* 좁은 화면의 가로 메뉴는 위아래 여백(py-1)을 둬 초점 고리가 잘리지 않게 한다 */}
          <ul className="-mx-4 flex gap-2 overflow-x-auto px-4 py-1 @min-[40rem]:-mx-6 @min-[40rem]:px-6 @min-[56rem]:mx-0 @min-[56rem]:grid @min-[56rem]:gap-1 @min-[56rem]:overflow-visible @min-[56rem]:px-0 @min-[56rem]:py-0">
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
      </div>
      <Outlet />
    </div>
  )
}

function ProfileCard({ me, pending }: { me: Me | undefined; pending: boolean }) {
  return (
    <div
      data-slot="profile-card"
      className="relative hidden overflow-hidden rounded-[18px] bg-ink-900 p-5 text-white @min-[56rem]:block"
    >
      <span
        aria-hidden="true"
        className="absolute -top-10 -right-10 size-32 rounded-full bg-brand-600/40 blur-2xl"
      />
      {me ? (
        <div className="relative grid gap-3">
          <span
            aria-hidden="true"
            className="inline-flex size-12 items-center justify-center rounded-full bg-brand-600 text-[20px] font-semibold"
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
        onFocus={revealInScroller}
        className={({ isActive }) =>
          cn(
            'flex h-11 items-center gap-2.5 rounded-[12px] px-3.5 type-body-sm font-semibold whitespace-nowrap outline-none focus-visible:ring-2 focus-visible:ring-ring',
            '@max-[56rem]:rounded-pill @max-[56rem]:border @max-[56rem]:border-gray-200 @max-[56rem]:bg-gray-0',
            // 현재 메뉴는 옅은 인디고 바탕 + 진한 인디고 글자다(시안). 다크에서는 두 값이 함께 바뀐다(2-5절)
            isActive
              ? 'bg-primary-50 text-primary-700 @max-[56rem]:border-primary-600 @max-[56rem]:bg-primary-50'
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
