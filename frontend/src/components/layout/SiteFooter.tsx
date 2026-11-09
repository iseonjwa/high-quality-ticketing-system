import { Link } from 'react-router'
import { paths } from '@/app/paths'
import { Logo } from '@/components/layout/Logo'

const REPOSITORY_URL = 'https://github.com/iseonjwa/high-quality-ticketing-system'

// 사이트 아래 정보. 실제 서비스처럼 안내·바로가기·운영 정보를 두되, 없는 약관·고객센터 링크는 만들지 않는다.
export function SiteFooter() {
  return (
    <footer className="mt-auto border-t border-gray-100 bg-gray-0 text-gray-500">
      <div className="mx-auto grid max-w-[1200px] gap-10 px-4 py-12 @min-[40rem]:px-6 @min-[56rem]:grid-cols-[1.4fr_1fr_1fr]">
        <div className="grid content-start gap-3">
          <Logo />
          <p className="type-body-sm max-w-[320px] text-gray-500">
            티켓 오픈의 폭주 트래픽을 견디는 콘서트 예매 서비스예요. 대기열, 좌석 선점, 추첨
            자동결제로 공정하게 예매해요.
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
            <li>
              <FooterLink to={paths.help}>예매 문의</FooterLink>
            </li>
          </ul>
        </nav>
        <div className="grid content-start gap-3">
          <h2 className="type-body-sm font-semibold text-gray-900">예매 안내</h2>
          <ul className="grid gap-2 type-body-sm">
            <li>예매는 대기열 → 좌석 선택 → 결제 순서예요.</li>
            <li>일부 좌석은 추첨으로 응모를 받아요.</li>
            <li>시연용 결제라 실제로 돈이 나가지 않아요.</li>
          </ul>
        </div>
      </div>
      <div className="border-t border-gray-100">
        <div className="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-5 type-caption text-gray-500 @min-[40rem]:px-6">
          <p>© 2026 이선좌 · 부트캠프 최종 프로젝트 5조</p>
          <a
            href={REPOSITORY_URL}
            target="_blank"
            rel="noreferrer"
            className="rounded-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
          >
            GitHub 저장소
          </a>
        </div>
      </div>
    </footer>
  )
}

function FooterLink({ to, children }: { to: string; children: string }) {
  return (
    <Link
      to={to}
      className="rounded-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
    >
      {children}
    </Link>
  )
}
