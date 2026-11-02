import { cn } from '@/lib/utils'

// 공연 이미지가 아직 API에 없어 샘플(.poster)처럼 색 면으로 자리만 잡는다. 꾸밈이라 읽지 않는다.
// 공연마다 다르게 보이도록 ID로 색 조합을 고른다.
const GRADIENTS = [
  'from-primary-700 to-seat-vip',
  'from-primary-600 to-info-600',
  'from-gray-800 to-primary-600',
] as const

export function ConcertPoster({ concertId, className }: { concertId: number; className?: string }) {
  return (
    <div
      aria-hidden="true"
      data-slot="concert-poster"
      className={cn(
        'h-[84px] w-16 shrink-0 rounded-sm bg-linear-150',
        GRADIENTS[concertId % GRADIENTS.length],
        className,
      )}
    />
  )
}
