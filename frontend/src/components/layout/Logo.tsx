import { cn } from '@/lib/utils'

// 이선좌 로고 "조명이 비춘 내 자리"(design-system.md 10절). 어두운 칸에 위에서 비추는 조명과 빨간 좌석 하나를 둔다.
// 빨간 좌석은 409 안내 "이미 선택된 좌석입니다"의 그 좌석이다. 파비콘 public/favicon.svg는 16px에서 뭉개지는 바닥 빛만 뺀
// 같은 그림이다.
// 칸은 모드와 상관없이 어두워 라이트·다크 어디서나 같은 표시로 보인다. 표시는 꾸밈이라 읽지 않고, 링크 이름은 글자 "이선좌"만 남긴다.
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 32 32"
      aria-hidden="true"
      className={cn('size-8 shrink-0', className)}
      focusable="false"
    >
      <rect width="32" height="32" rx="9" fill="#1d2030" />
      <path d="M13 6.2H19L27 25H5Z" fill="#fff" opacity="0.18" />
      <rect x="12" y="3" width="8" height="3.2" rx="1.6" fill="#fff" />
      <ellipse cx="16" cy="26.4" rx="10" ry="1.6" fill="#fff" opacity="0.22" />
      <rect x="11" y="15" width="10" height="6.5" rx="2" fill="#ff5b4a" />
      <rect x="9" y="20.5" width="14" height="4" rx="1.8" fill="#ff5b4a" />
    </svg>
  )
}

// onImage: 그림 위처럼 모드와 상관없이 어두운 바탕에 얹을 때 글자를 흰색으로 고정한다.
// compact: 앱 화면이 28rem보다 좁으면 표시만 보이고 글자는 화면 낭독기만 읽는다(헤더 메뉴가 한 줄에 들어가게)
export function Logo({
  onImage = false,
  compact = false,
  className,
}: {
  onImage?: boolean
  compact?: boolean
  className?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 text-[21px] font-extrabold tracking-[-0.05em]',
        onImage ? 'text-white' : 'text-gray-900',
        className,
      )}
    >
      <LogoMark />
      <span className={cn(compact && '@max-[28rem]:sr-only')}>이선좌</span>
    </span>
  )
}
