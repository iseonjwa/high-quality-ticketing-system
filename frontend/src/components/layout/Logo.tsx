import { cn } from '@/lib/utils'

// 이선좌 로고. 좌석 모양 표시에 "이미 선택된 좌석"을 뜻하는 빨간 점을 단다(파비콘 public/favicon.svg와 같은 그림).
// 표시는 꾸밈이라 읽지 않고, 링크 이름은 글자 "이선좌"만 남긴다.
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className={cn('size-7 shrink-0', className)}
      focusable="false"
    >
      <rect width="24" height="24" rx="6" fill="var(--primary-600)" />
      <rect x="7" y="5" width="10" height="8.5" rx="2.5" fill="#fff" />
      <rect x="5" y="12" width="14" height="4.5" rx="2" fill="#fff" />
      <rect x="7" y="16" width="2" height="3.5" rx="1" fill="#fff" />
      <rect x="15" y="16" width="2" height="3.5" rx="1" fill="#fff" />
      <circle
        cx="18.3"
        cy="5.7"
        r="3"
        fill="#ff5b4a"
        stroke="var(--primary-600)"
        strokeWidth="1.4"
      />
    </svg>
  )
}

export function Logo({
  tone = 'dark',
  className,
}: {
  tone?: 'dark' | 'light'
  className?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 text-[20px] font-extrabold tracking-[-0.03em]',
        tone === 'dark' ? 'text-gray-900' : 'text-white',
        className,
      )}
    >
      <LogoMark />
      이선좌
    </span>
  )
}
