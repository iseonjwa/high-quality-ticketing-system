import { Link } from 'react-router'
import { paths } from '@/app/paths'
import { AssistantOpenButton } from '@/components/assistant/AssistantOpenButton'
import { useAssistantWidget } from '@/components/assistant/assistantWidgetContext'
import { Logo } from '@/components/layout/Logo'
import { disableDemoMode, enableDemoMode, useIsDemoMode } from '@/demo/demoMode'
import { cn } from '@/lib/utils'

const REPOSITORY_URL = 'https://github.com/iseonjwa/high-quality-ticketing-system'

// 사이트 아래 정보. 실제 서비스처럼 안내·바로가기·운영 정보를 두되, 없는 약관·고객센터 링크는 만들지 않는다.
export function SiteFooter() {
  return (
    <footer className="mt-auto border-t border-gray-100 bg-gray-0 text-gray-500">
      <div className="mx-auto grid max-w-[1200px] gap-10 px-4 py-12 @min-[40rem]:px-6 @min-[56rem]:grid-cols-[1.4fr_1fr_1fr]">
        <div className="grid content-start gap-3">
          <Logo />
          <p className="type-body-sm max-w-[320px] text-gray-500">
            티켓 오픈의 폭주 트래픽을 견디는 콘서트 예매 서비스입니다. 대기열, 좌석 선점, 추첨
            자동결제로 공정하게 예매할 수 있습니다.
          </p>
        </div>
        <nav aria-label="바로가기" className="grid content-start gap-3">
          <h2 className="type-body-sm font-semibold text-gray-900">바로가기</h2>
          <ul className="grid gap-2 type-body-sm">
            <li>
              <FooterLink to={paths.home}>공연</FooterLink>
            </li>
            <li>
              <FooterLink to={paths.myReservations}>내 예매</FooterLink>
            </li>
            <li>
              <FooterLink to={paths.me}>내 정보·자동결제 카드</FooterLink>
            </li>
            <AssistantFooterItem />
          </ul>
        </nav>
        <div className="grid content-start gap-3">
          <h2 className="type-body-sm font-semibold text-gray-900">예매 안내</h2>
          <ul className="grid gap-2 type-body-sm">
            <li>예매는 대기열 → 좌석 선택 → 결제 순서로 진행됩니다.</li>
            <li>일부 좌석은 추첨으로 응모를 받습니다.</li>
            <li>시연용 결제이므로 실제 결제는 이루어지지 않습니다.</li>
          </ul>
        </div>
      </div>
      <div className="border-t border-gray-100">
        <div className="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-5 type-caption text-gray-500 @min-[40rem]:px-6">
          {/* 시연 모드 버튼은 왼쪽에 둔다. 오른쪽 아래는 떠 있는 [예매 문의] 버튼이 가린다 */}
          <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
            <p>© 2026 이선좌 · 부트캠프 최종 프로젝트 5조</p>
            <DemoModeToggle />
          </div>
          <a href={REPOSITORY_URL} target="_blank" rel="noreferrer" className={FOOTER_LINK}>
            GitHub 저장소
          </a>
        </div>
      </div>
    </footer>
  )
}

// 예매 문의는 위젯을 연다. 위젯이 없는 화면이면 항목을 두지 않는다
function AssistantFooterItem() {
  const { available } = useAssistantWidget()
  return available ? (
    <li>
      <AssistantOpenButton className={FOOTER_LINK}>예매 문의</AssistantOpenButton>
    </li>
  ) : null
}

// 발표·심사 때 주소를 고치지 않고 시연 모드(요청 로그 패널)를 켜고 끈다. 켜는 방식은 `?demo=on`과 같다(탭 단위, 기본 꺼짐).
// 패널을 그리지 않는 640px 미만 화면에서는 버튼도 숨긴다(demo/panelWidth.ts PANEL_VISIBLE_MIN_VIEWPORT와 같은 기준)
function DemoModeToggle() {
  const enabled = useIsDemoMode()
  return (
    <button
      type="button"
      aria-pressed={enabled}
      className={cn(FOOTER_LINK, 'max-sm:hidden')}
      onClick={() => (enabled ? disableDemoMode() : enableDemoMode())}
    >
      {enabled ? '시연 모드 끄기' : '시연 모드 켜기'}
    </button>
  )
}

const FOOTER_LINK =
  'rounded-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring'

function FooterLink({ to, children }: { to: string; children: string }) {
  return (
    <Link to={to} className={FOOTER_LINK}>
      {children}
    </Link>
  )
}
