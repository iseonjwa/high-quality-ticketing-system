import { concertArt, POSTER_SIZE } from '@/components/ticketing/concertArt'
import { cn } from '@/lib/utils'

// 공연 포스터. 그림은 공연 번호로 고른다(concertArt). 꾸밈이라 읽지 않는다. 공연 제목은 화면이 따로 읽힌다.
// title을 주면 실제 포스터처럼 그림 아래쪽에 제목을 얹는다. 글자 크기는 포스터 폭(cqw)에 맞춘다.
// 폭은 className으로 정하고 높이는 2:3 비율로 따라간다. 화면 위쪽에 바로 보이는 포스터만 loading="eager"로 둔다.
export function ConcertPoster({
  concertId,
  title,
  className,
  loading = 'lazy',
}: {
  concertId: number
  title?: string
  className?: string
  loading?: 'lazy' | 'eager'
}) {
  const art = concertArt(concertId)
  const light = art.tone === 'light'
  return (
    <div
      aria-hidden="true"
      data-slot="concert-poster"
      className={cn(
        '@container relative aspect-[2/3] w-16 shrink-0 overflow-hidden rounded-sm bg-gray-800',
        className,
      )}
    >
      <img
        src={art.poster}
        alt=""
        width={POSTER_SIZE.width}
        height={POSTER_SIZE.height}
        loading={loading}
        decoding="async"
        draggable={false}
        className="absolute inset-0 size-full object-cover"
      />
      {title ? (
        <div
          className={cn(
            'absolute inset-x-0 bottom-0 grid gap-[3cqw] px-[8cqw] pt-[24cqw] pb-[8cqw]',
            light
              ? 'bg-linear-to-t from-[#efeae0] via-[#efeae0]/85 to-transparent text-gray-900'
              : 'bg-linear-to-t from-black/85 via-black/45 to-transparent text-white',
          )}
        >
          <span
            className={cn(
              'text-[5.5cqw] leading-none font-semibold tracking-[0.18em] uppercase',
              light ? 'text-primary-600' : 'text-white/70',
            )}
          >
            Live Concert
          </span>
          <span className="line-clamp-3 text-[10.5cqw] leading-[1.12] font-extrabold tracking-[-0.03em] break-keep">
            {title}
          </span>
        </div>
      ) : null}
    </div>
  )
}
