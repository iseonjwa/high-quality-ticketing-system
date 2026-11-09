import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react'
import { useEffect, useRef, useState, type FocusEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { concertPath } from '@/app/paths'
import { concertArt, WIDE_SIZE } from '@/components/ticketing/concertArt'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Button } from '@/components/ui/button'
import { formatDateTime, kstDaysUntil } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { HomeConcert } from '@/pages/concerts/homeConcerts'

// 홈 맨 위의 대표 배너. 예매 중·오픈 예정 공연을 몇 개 골라 넘겨 보인다.
// 자동 넘김은 WAI-ARIA 캐러셀 패턴을 따른다: 멈춤 버튼을 맨 앞에 두고, 마우스를 올리면 그동안만 멈춘다.
// 키보드 초점이 밖에서 들어오면 멈추고 [자동 넘김 시작]을 누르기 전에는 다시 넘기지 않는다. 동작 줄이기 설정이면
// 처음부터 멈춰 둔다. 보이지 않는 장은 inert로 초점·낭독에서 뺀다.
const ROTATE_MILLIS = 7000

const HERO_HEIGHT = 'h-[clamp(440px,46cqw,540px)]'

export function HomeHero({ items, nowMillis }: { items: HomeConcert[]; nowMillis: number }) {
  return items.length === 0 ? (
    <BrandHero />
  ) : (
    <FeaturedCarousel items={items} nowMillis={nowMillis} />
  )
}

function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

function FeaturedCarousel({ items, nowMillis }: { items: HomeConcert[]; nowMillis: number }) {
  const [index, setIndex] = useState(0)
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const [hovering, setHovering] = useState(false)
  const count = items.length
  // 공연 묶음이 줄어 지금 장이 사라지면 마지막 장을 보인다
  const current = Math.min(index, count - 1)
  // 배너 그림을 받은 장. 지금 장과 다음 장만 받고(나머지는 넘길 때 받는다), 한 번 받은 그림은 남긴다
  const next = (current + 1) % count
  const [loaded, setLoaded] = useState<ReadonlySet<number>>(() => new Set([0, 1 % count]))
  if (!loaded.has(current) || !loaded.has(next)) {
    setLoaded(new Set(loaded).add(current).add(next))
  }
  const rotating = playing && !hovering && count > 1

  useEffect(() => {
    if (!rotating) {
      return
    }
    const timer = setInterval(() => setIndex((value) => (value + 1) % count), ROTATE_MILLIS)
    return () => clearInterval(timer)
  }, [rotating, count])

  // 사용자가 직접 넘기면 자동 넘김을 끈다(다시 켜려면 재생 버튼)
  const go = (next: number) => {
    setPlaying(false)
    setIndex(((next % count) + count) % count)
  }

  // 키보드 초점이 캐러셀 밖에서 들어올 때만 멈춘다. 마우스로 버튼을 누를 때 생기는 초점은 세지 않는다.
  // 세면 [자동 넘김 멈추기]를 누르는 순간 초점으로 먼저 멈추고 이어진 클릭이 다시 켠다
  const pointerDownRef = useRef(false)
  const onFocus = (event: FocusEvent<HTMLElement>) => {
    if (!pointerDownRef.current && !event.currentTarget.contains(event.relatedTarget)) {
      setPlaying(false)
    }
  }

  return (
    <section
      aria-roledescription="carousel"
      aria-label="추천 공연"
      // isolate: 안의 z-index(조작판)가 sticky 헤더 위로 올라가지 않게 쌓임 맥락을 가둔다
      className={cn('relative isolate overflow-hidden bg-ink-900 text-white', HERO_HEIGHT)}
      onMouseEnter={() => setHovering(true)}
      onMouseLeave={() => setHovering(false)}
      onPointerDown={() => {
        pointerDownRef.current = true
      }}
      onPointerUp={() => {
        pointerDownRef.current = false
      }}
      onFocus={onFocus}
    >
      {count > 1 ? (
        <div className="absolute inset-x-0 bottom-6 z-20">
          <div className="mx-auto flex max-w-[1200px] px-4 @min-[40rem]:px-6">
            <div className="flex items-center gap-1 rounded-pill bg-black/35 p-1 backdrop-blur">
              <HeroControl
                label={playing ? '자동 넘김 멈추기' : '자동 넘김 시작'}
                onClick={() => setPlaying((value) => !value)}
              >
                {playing ? <Pause /> : <Play />}
              </HeroControl>
              <HeroControl label="이전 공연" onClick={() => go(current - 1)}>
                <ChevronLeft />
              </HeroControl>
              <span className="min-w-12 text-center type-caption tabular-nums text-white/90">
                {current + 1} / {count}
              </span>
              <HeroControl label="다음 공연" onClick={() => go(current + 1)}>
                <ChevronRight />
              </HeroControl>
            </div>
          </div>
        </div>
      ) : null}
      {/* 자동으로 넘어가는 동안에는 장이 바뀔 때마다 읽지 않는다 */}
      <div aria-live={rotating ? 'off' : 'polite'} className="absolute inset-0">
        {items.map((item, position) => (
          <HeroSlide
            key={item.concert.concertId}
            item={item}
            active={position === current}
            label={`${position + 1} / ${count}`}
            eager={position === 0}
            loadArt={loaded.has(position)}
            nowMillis={nowMillis}
          />
        ))}
      </div>
    </section>
  )
}

function HeroControl({
  label,
  onClick,
  children,
}: {
  label: string
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="inline-flex size-9 items-center justify-center rounded-full text-white outline-none hover:bg-white/15 focus-visible:ring-2 focus-visible:ring-white [&>svg]:size-4"
    >
      {children}
    </button>
  )
}

function HeroSlide({
  item,
  active,
  label,
  eager,
  loadArt,
  nowMillis,
}: {
  item: HomeConcert
  active: boolean
  label: string
  eager: boolean
  // false면 배너 그림을 아직 받지 않는다(보이지 않는 장의 그림을 첫 화면에서 받지 않게)
  loadArt: boolean
  nowMillis: number
}) {
  const { concert, next, status } = item
  const art = concertArt(concert.concertId)
  return (
    <div
      role="group"
      aria-roledescription="slide"
      aria-label={label}
      inert={!active}
      aria-hidden={active ? undefined : true}
      className={cn(
        'absolute inset-0 transition-opacity duration-700 ease-out motion-reduce:transition-none',
        active ? 'opacity-100' : 'opacity-0',
      )}
    >
      {loadArt ? (
        <img
          src={art.wide}
          alt=""
          width={WIDE_SIZE.width}
          height={WIDE_SIZE.height}
          fetchPriority={eager ? 'high' : 'auto'}
          decoding="async"
          className="absolute inset-0 size-full object-cover"
        />
      ) : null}
      {/* 글자가 그림 위에서 읽히도록 왼쪽과 아래를 어둡게 덮는다 */}
      {/* 좁은 화면에서는 글자가 폭 전체를 쓰므로 그라데이션 대신 고르게 덮는다(밝은 그림에서도 흰 글자 4.5:1) */}
      <div className="absolute inset-0 bg-linear-to-r from-ink-900/95 via-ink-900/75 to-ink-900/10 @max-[56rem]:bg-none @max-[56rem]:bg-ink-900/75" />
      <div className="absolute inset-x-0 bottom-0 h-32 bg-linear-to-t from-ink-900/70 to-transparent" />
      <div className="relative mx-auto grid h-full max-w-[1200px] items-center gap-10 px-4 @min-[40rem]:px-6 @min-[56rem]:grid-cols-[1fr_auto]">
        <div className="grid max-w-[620px] gap-5 pb-12">
          <div className="flex flex-wrap items-center gap-2">
            {status ? <SaleStatusBadge status={status} /> : null}
            {next && status ? <DayChip status={status} next={next} nowMillis={nowMillis} /> : null}
          </div>
          <h2 className="text-[clamp(28px,4.2cqw,46px)] leading-[1.15] font-extrabold tracking-[-0.03em] break-keep">
            {concert.title}
          </h2>
          {next ? (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 type-body text-white/90">
              <dt className="text-white/70">공연</dt>
              <dd className="tabular-nums">{formatDateTime(next.startsAt)}</dd>
              <dt className="text-white/70">오픈</dt>
              <dd className="tabular-nums">{formatDateTime(next.openAt)}</dd>
            </dl>
          ) : null}
          <div>
            <Button asChild size="lg" className="shadow-lg">
              <Link to={concertPath(concert.concertId)}>
                {status === 'ON_SALE' ? '예매하러 가기' : '공연 보기'}
              </Link>
            </Button>
          </div>
        </div>
        {/* 넓은 화면에서만 보인다. lazy라 좁은 화면(display:none)에서는 받지 않는다 */}
        {loadArt ? (
          <ConcertPoster
            concertId={concert.concertId}
            title={concert.title}
            className="hidden w-[220px] -rotate-2 rounded-md shadow-[0_24px_60px_rgba(0,0,0,0.55)] ring-1 ring-white/10 @min-[56rem]:block @min-[72rem]:w-[250px]"
          />
        ) : null}
      </div>
    </div>
  )
}

// "오픈 D-3", "오늘 공연"처럼 다음 일정까지 남은 날
function DayChip({
  status,
  next,
  nowMillis,
}: {
  status: HomeConcert['status']
  next: NonNullable<HomeConcert['next']>
  nowMillis: number
}) {
  const label = status === 'BEFORE_OPEN' ? '오픈' : status === 'ON_SALE' ? '공연' : null
  if (label === null) {
    return null
  }
  const days = kstDaysUntil(status === 'BEFORE_OPEN' ? next.openAt : next.startsAt, nowMillis)
  return (
    <span className="inline-flex h-6 items-center rounded-pill bg-white/15 px-2.5 text-[12px] font-semibold text-white tabular-nums">
      {days <= 0 ? `오늘 ${label}` : `${label} D-${days}`}
    </span>
  )
}

// 보일 공연이 없을 때의 배너. 서비스 소개만 한다
function BrandHero() {
  const art = concertArt(1)
  return (
    <section
      aria-label="이선좌 소개"
      className={cn('relative overflow-hidden bg-ink-900 text-white', HERO_HEIGHT)}
    >
      <img
        src={art.wide}
        alt=""
        width={WIDE_SIZE.width}
        height={WIDE_SIZE.height}
        fetchPriority="high"
        decoding="async"
        className="absolute inset-0 size-full object-cover"
      />
      <div className="absolute inset-0 bg-linear-to-r from-ink-900/95 via-ink-900/75 to-ink-900/10 @max-[56rem]:bg-none @max-[56rem]:bg-ink-900/75" />
      <div className="relative mx-auto grid h-full max-w-[1200px] content-center gap-4 px-4 @min-[40rem]:px-6">
        <p className="type-body font-semibold text-primary-300">콘서트 예매, 이선좌</p>
        <h2 className="max-w-[620px] text-[clamp(28px,4.2cqw,46px)] leading-[1.15] font-extrabold tracking-[-0.03em] break-keep">
          오픈 순간에도 흔들리지 않는 예매
        </h2>
        <p className="max-w-[520px] type-body text-white/80">
          대기열로 순서를 지키고, 고른 좌석은 결제하는 동안 잡아 둬요. 일부 좌석은 추첨으로 공정하게
          나눠요.
        </p>
      </div>
    </section>
  )
}
