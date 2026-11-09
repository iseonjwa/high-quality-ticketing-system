import { ChevronLeft, ChevronRight, Pause, Play } from 'lucide-react'
import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type ReactNode,
} from 'react'
import { Link } from 'react-router'
import { concertPath } from '@/app/paths'
import { concertArt, POSTER_SIZE, WIDE_SIZE } from '@/components/ticketing/concertArt'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Button } from '@/components/ui/button'
import { formatDateTime, kstDaysUntil, kstParts } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { useNow } from '@/lib/serverClock'
import { cn } from '@/lib/utils'
import type { HomeConcert } from '@/pages/concerts/homeConcerts'

// 홈 맨 위의 대표 배너(시안 B). 예매 중·오픈 예정 공연을 몇 개 골라 넘겨 보인다.
// 자동 넘김은 WAI-ARIA 캐러셀 패턴을 따른다: 멈춤 버튼을 맨 앞에 두고, 마우스를 올리면 그동안만 멈춘다.
// 키보드 초점이 밖에서 들어오면 멈추고 [자동 넘김 시작]을 누르기 전에는 다시 넘기지 않는다. 동작 줄이기 설정이면
// 처음부터 멈춰 둔다. 보이지 않는 장은 inert로 초점·낭독에서 뺀다.
const ROTATE_MILLIS = 7000

// 목록을 받는 동안 ConcertListPage가 같은 높이로 자리를 잡는다
export const HERO_HEIGHT = 'h-[clamp(500px,48cqw,620px)]'

// 그림 위 글자: 페이지 바탕색으로 왼쪽을 짙게 덮는다. 라이트는 흰 막에 검은 글자, 다크는 검은 막에 흰 글자가 된다.
// 좁은 화면(56rem 미만)은 글자가 폭 전체를 쓰므로 고르게 덮는다(밝은 그림·어두운 그림 모두 본문 4.5:1)
const ART_WASH =
  'absolute inset-0 bg-linear-to-r from-gray-25/95 via-gray-25/85 to-gray-25/15 @max-[56rem]:bg-none @max-[56rem]:bg-gray-25/85'

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
  // Note: 지금 장은 순서가 아니라 공연 번호로 기억한다. 홈은 10초마다 묶음을 다시 나누는데, 보던 공연이 오픈해
  // 예매 중 묶음으로 옮겨 가면 순서가 바뀐다. 순서로 기억하면 0초를 지켜보던 장이 다른 공연으로 바뀐다(셀프 리뷰)
  const [shown, setShown] = useState(() => ({ id: items[0].concert.concertId, position: 0 }))
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const [hovering, setHovering] = useState(false)
  const count = items.length
  const current = currentPosition(items, shown)
  // 배너 그림을 받은 공연. 지금 장과 다음 장만 받고(나머지는 넘길 때 받는다), 한 번 받은 그림은 남긴다
  const currentId = items[current].concert.concertId
  const nextId = items[(current + 1) % count].concert.concertId
  const [loaded, setLoaded] = useState<ReadonlySet<number>>(() => new Set([currentId, nextId]))
  if (!loaded.has(currentId) || !loaded.has(nextId)) {
    setLoaded(new Set(loaded).add(currentId).add(nextId))
  }
  const rotating = playing && !hovering && count > 1

  // 자동 넘김 타이머는 묶음이 다시 나뉘어도 새로 걸지 않는다. 넘길 때 최신 묶음을 읽는다
  const itemsRef = useRef(items)
  useEffect(() => {
    itemsRef.current = items
  })
  useEffect(() => {
    if (!rotating) {
      return
    }
    const timer = setInterval(
      () =>
        setShown((value) =>
          shownAt(itemsRef.current, currentPosition(itemsRef.current, value) + 1),
        ),
      ROTATE_MILLIS,
    )
    return () => clearInterval(timer)
  }, [rotating])

  // 사용자가 직접 넘기면 자동 넘김을 끈다(다시 켜려면 재생 버튼)
  const go = (position: number) => {
    setPlaying(false)
    setShown(shownAt(items, position))
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
      className={cn('relative isolate overflow-hidden bg-gray-25 text-gray-900', HERO_HEIGHT)}
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
          <div className="mx-auto flex max-w-[1200px] items-center gap-3 px-4 @min-[40rem]:px-6">
            <HeroControl
              label={playing ? '자동 넘김 멈추기' : '자동 넘김 시작'}
              onClick={() => setPlaying((value) => !value)}
            >
              {playing ? <Pause /> : <Play />}
            </HeroControl>
            <HeroControl label="이전 공연" onClick={() => go(current - 1)}>
              <ChevronLeft />
            </HeroControl>
            <span className="min-w-12 text-center type-body-sm font-semibold text-gray-700 tabular-nums">
              {current + 1} / {count}
            </span>
            <HeroControl label="다음 공연" onClick={() => go(current + 1)}>
              <ChevronRight />
            </HeroControl>
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
            loadArt={loaded.has(item.concert.concertId)}
            nowMillis={nowMillis}
          />
        ))}
      </div>
    </section>
  )
}

type Shown = { id: number; position: number }

// 지금 보이는 장. 보던 공연이 묶음에서 빠지면(판매 종료 등) 같은 자리, 그 자리도 없으면 마지막 장을 보인다
function currentPosition(items: HomeConcert[], shown: Shown): number {
  const found = items.findIndex((item) => item.concert.concertId === shown.id)
  return found === -1 ? Math.min(shown.position, items.length - 1) : found
}

function shownAt(items: HomeConcert[], position: number): Shown {
  const wrapped = ((position % items.length) + items.length) % items.length
  return { id: items[wrapped].concert.concertId, position: wrapped }
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
      className="inline-flex size-9 items-center justify-center rounded-full border border-gray-300 bg-gray-0/70 text-gray-900 outline-none backdrop-blur hover:bg-gray-0 focus-visible:ring-2 focus-visible:ring-ring [&>svg]:size-4"
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
      <div className={ART_WASH} />
      <div className="absolute inset-x-0 bottom-0 h-28 bg-linear-to-t from-gray-25 to-transparent" />
      <div className="relative mx-auto grid h-full max-w-[1200px] items-center gap-10 px-4 @min-[40rem]:px-6 @min-[56rem]:grid-cols-[1fr_auto]">
        <div className="grid max-w-[640px] gap-5 pb-16">
          <div className="flex flex-wrap items-center gap-2">
            {status ? <SaleStatusBadge status={status} /> : null}
            {next && status ? <HeroChip status={status} next={next} nowMillis={nowMillis} /> : null}
          </div>
          <h2 className="text-[clamp(32px,5cqw,64px)] leading-[1.06] font-extrabold tracking-[-0.045em] break-keep">
            {concert.title}
          </h2>
          {next ? (
            <p className="type-body text-gray-700 tabular-nums">
              {formatDateTime(next.startsAt)} 공연
            </p>
          ) : null}
          {next && status === 'BEFORE_OPEN' ? (
            <HeroCountdown openAt={next.openAt} active={active} nowMillis={nowMillis} />
          ) : null}
          <div>
            <Button asChild size="lg" className="rounded-pill px-7">
              <Link to={concertPath(concert.concertId)}>
                {status === 'ON_SALE' ? '예매하러 가기' : '공연 보기'}
              </Link>
            </Button>
          </div>
        </div>
        {/* 넓은 화면에서만 보인다. lazy라 좁은 화면(display:none)에서는 받지 않는다 */}
        {loadArt && next ? (
          <HeroPass concertId={concert.concertId} startsAt={next.startsAt} />
        ) : null}
      </div>
    </div>
  )
}

// 오픈 예정이면 "2026.11.07 (토) 12:00 티켓 오픈", 예매 중이면 "공연 D-3"
function HeroChip({
  status,
  next,
  nowMillis,
}: {
  status: HomeConcert['status']
  next: NonNullable<HomeConcert['next']>
  nowMillis: number
}) {
  if (status === 'BEFORE_OPEN') {
    return (
      <span className="inline-flex h-7 items-center gap-2 rounded-pill bg-danger-50 px-3 type-caption font-semibold text-gray-900 ring-1 ring-danger-600/30 tabular-nums">
        <span aria-hidden="true" className="size-2 rounded-full bg-[#ff5b4a]" />
        {formatDateTime(next.openAt)} 티켓 오픈
      </span>
    )
  }
  if (status !== 'ON_SALE') {
    return null
  }
  const days = kstDaysUntil(next.startsAt, nowMillis)
  return (
    <span className="inline-flex h-6 items-center rounded-pill bg-gray-0/80 px-2.5 text-[12px] font-semibold text-gray-700 ring-1 ring-gray-200 tabular-nums">
      {days <= 0 ? '오늘 공연' : `공연 D-${days}`}
    </span>
  )
}

const SECOND = 1000

// 오픈까지 남은 시간을 일·시간·분·초 칸으로 보인다. 초는 올림이다(design-system.md 7절).
// Note: 목록 응답에는 서버 시각이 없어(ISE-99) 브라우저 시계로 센다. 보여 주기만 하고, [예매하기]가 켜지는 순간은
// 서버 시각으로 보정하는 공연 상세가 정한다. 1초 시계는 보이는 장만 돌리고, 숨은 장은 묶음 시계(10초)로 그린다.
function HeroCountdown({
  openAt,
  active,
  nowMillis,
}: {
  openAt: string
  active: boolean
  nowMillis: number
}) {
  return active ? (
    <LiveCountdown openAt={openAt} />
  ) : (
    <CountdownUnits total={secondsUntil(openAt, nowMillis)} />
  )
}

function secondsUntil(openAt: string, nowMillis: number): number {
  return Math.max(0, Math.ceil((instantMillis(openAt) - nowMillis) / SECOND))
}

function LiveCountdown({ openAt }: { openAt: string }) {
  const total = secondsUntil(openAt, useNow(SECOND))
  // 0이 된 뒤 묶음을 다시 나눌 때까지(최대 10초) "오픈 예정" 배지가 남는다. 0칸 대신 문장으로 바꿔 모순으로 보이지 않게 한다
  return total === 0 ? (
    <p className="type-body font-semibold text-gray-900">
      티켓이 열렸어요. 공연 보기에서 예매해 주세요
    </p>
  ) : (
    <CountdownUnits total={total} />
  )
}

// Note: 칸은 낭독하지 않는다(aria-hidden). 장 묶음이 멈추면 polite 알림 영역이 되는데, 그 안에서 매초 바뀌는 숫자는
// timer 역할이어도 Safari(VoiceOver)가 읽을 수 있다(셀프 리뷰). 같은 정보는 칩의 "오픈 일시 티켓 오픈"이 가진다
function CountdownUnits({ total }: { total: number }) {
  const units = [
    { label: '일', value: Math.floor(total / 86400) },
    { label: '시간', value: Math.floor((total % 86400) / 3600) },
    { label: '분', value: Math.floor((total % 3600) / 60) },
    { label: '초', value: total % 60 },
  ]
  return (
    <div aria-hidden="true" data-slot="hero-countdown" className="flex flex-wrap gap-2.5">
      {units.map((unit) => (
        <span
          key={unit.label}
          className="grid min-w-[72px] justify-items-center rounded-[14px] border border-gray-200 bg-gray-0/70 px-2 py-2.5 backdrop-blur"
        >
          <span className="text-[30px] leading-9 font-bold tracking-[-0.02em] tabular-nums">
            {String(unit.value).padStart(2, '0')}
          </span>
          <span className="type-caption text-gray-600">{unit.label}</span>
        </span>
      ))}
    </div>
  )
}

const PASS_CUT = 296

// 그림 위에 기울여 놓은 입장권. 포스터 아래 절취선에서 양옆을 실제로 파낸다(.ticket-pass).
// 꾸밈이라 읽지 않는다(제목·일시는 왼쪽 글자가 같은 내용을 가진다)
// Note: mask는 테두리 밖 box-shadow까지 자른다. 그림자는 바깥 래퍼의 drop-shadow로 그려, 파낸 홈 모양까지 따라가게 한다
function HeroPass({ concertId, startsAt }: { concertId: number; startsAt: string }) {
  const art = concertArt(concertId)
  const when = kstParts(startsAt)
  return (
    <div
      aria-hidden="true"
      data-slot="hero-pass"
      className="hidden rotate-3 [filter:drop-shadow(0_24px_30px_rgba(0,0,0,0.35))] @min-[56rem]:block"
    >
      <div
        style={{ '--cut': `${PASS_CUT}px` } as CSSProperties}
        className="ticket-pass relative h-[440px] w-[280px] rounded-[22px] bg-gray-0 text-gray-900"
      >
        <div className="p-3">
          <img
            src={art.poster}
            alt=""
            width={POSTER_SIZE.width}
            height={POSTER_SIZE.height}
            loading="lazy"
            decoding="async"
            className="h-[272px] w-full rounded-[14px] object-cover"
          />
        </div>
        <span
          className="absolute inset-x-4 border-t-2 border-dashed border-gray-200"
          style={{ top: PASS_CUT - 1 }}
        />
        <div className="absolute inset-x-5 grid gap-2.5" style={{ top: PASS_CUT + 16 }}>
          <div className="flex justify-between text-[11px] font-semibold tracking-[0.2em] text-gray-500">
            <span>ADMIT ONE</span>
            <span>TICKET</span>
          </div>
          <div className="flex items-baseline justify-between">
            <span className="text-[32px] leading-none font-bold tracking-[-0.03em] tabular-nums">
              {when.month}.{when.day}
            </span>
            <span className="type-body-sm font-semibold text-gray-700 tabular-nums">
              {when.weekday}요일 {when.hour}:{when.minute}
            </span>
          </div>
          <span className="block h-[34px] bg-[repeating-linear-gradient(90deg,currentColor_0_2px,transparent_2px_4px,currentColor_4px_7px,transparent_7px_9px,currentColor_9px_10px,transparent_10px_13px)]" />
        </div>
      </div>
    </div>
  )
}

// 보일 공연이 없을 때의 배너. 서비스 소개만 한다
function BrandHero() {
  const art = concertArt(1)
  return (
    <section
      aria-label="이선좌 소개"
      className={cn('relative overflow-hidden bg-gray-25 text-gray-900', HERO_HEIGHT)}
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
      <div className={ART_WASH} />
      <div className="relative mx-auto grid h-full max-w-[1200px] content-center gap-4 px-4 @min-[40rem]:px-6">
        <p className="type-body font-semibold text-primary-600">콘서트 예매, 이선좌</p>
        <h2 className="max-w-[640px] text-[clamp(32px,5cqw,64px)] leading-[1.06] font-extrabold tracking-[-0.045em] break-keep">
          오픈 순간에도 흔들리지 않는 예매
        </h2>
        <p className="max-w-[520px] type-body text-gray-700">
          대기열로 순서를 지키고, 고른 좌석은 결제하는 동안 잡아 둬요. 일부 좌석은 추첨으로 공정하게
          나눠요.
        </p>
      </div>
    </section>
  )
}
