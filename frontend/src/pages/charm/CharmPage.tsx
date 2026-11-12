import { Download, Share2 } from 'lucide-react'
import { useEffect, useMemo, useState, type CSSProperties, type PointerEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { paths } from '@/app/paths'
import { Button } from '@/components/ui/button'
import { kstParts } from '@/lib/format'
import { cn } from '@/lib/utils'
import { renderCharmPng } from '@/pages/charm/charmImage'
import { readCharmPayload, type CharmPayload } from '@/pages/charm/charmLink'
import { petTitle } from '@/pages/charm/charmPets'
import {
  CHEERS,
  charmNickname,
  charmSvg,
  pickCharm,
  type CharmData,
} from '@/pages/charm/charmTicket'

// 발권 이벤트 부적 화면(ISE-257). 예매 상세의 QR을 폰으로 찍으면 열린다. 로그인 없이 주소 조각만으로 그리고,
// 앱 머리(헤더) 없이 부적 한 장을 화면 가득 보인다. 저장한 PNG는 1080×1920(9:16)이라 인스타그램 스토리에 그대로 올라간다.

// 티켓 번호(T + 발권일 yyyyMMdd + "-" + 8자)의 발권일. 공연 일시를 모를 때 떼는 칸에 대신 쓴다
const ISSUED_DATE = /^T\d{4}(\d{2})(\d{2})-/

function charmData(payload: CharmPayload): CharmData {
  const when = payload.s ? kstParts(payload.s) : null
  const issued = ISSUED_DATE.exec(payload.k)
  return {
    nickname: payload.n,
    pick: pickCharm(payload.r, payload.i),
    concertTitle: payload.t ?? '이선좌 공연 티켓',
    dateLabel: when ? `${when.month}.${when.day}` : issued ? `${issued[1]}.${issued[2]}` : '',
    timeLabel: when ? `${when.weekday}요일 ${when.hour}:${when.minute}` : '발권일',
    seatLabel: payload.z,
    barcodeValue: payload.k,
  }
}

export function CharmPage() {
  const { hash } = useLocation()
  const payload = useMemo(() => readCharmPayload(hash), [hash])
  return (
    <main className="charm-page flex min-h-dvh flex-col items-center gap-5 px-4 pt-6 pb-10 text-white">
      {payload ? <CharmView key={hash} payload={payload} /> : <CharmMissing />}
    </main>
  )
}

function CharmMissing() {
  return (
    <div className="my-auto grid max-w-[360px] justify-items-center gap-4 text-center">
      <h1 className="type-title">부적을 찾지 못했어요</h1>
      <p className="type-body text-white/75">
        예매 상세 화면의 QR을 다시 찍어 주세요. QR 주소가 잘리면 부적을 그릴 수 없어요.
      </p>
      <Button asChild size="lg" className="bg-white text-primary-700 hover:bg-white/90">
        <Link to={paths.home}>이선좌 홈으로</Link>
      </Button>
    </div>
  )
}

type PngState =
  { status: 'pending' } | { status: 'ready'; url: string; file: File } | { status: 'failed' }

function CharmView({ payload }: { payload: CharmPayload }) {
  const data = useMemo(() => charmData(payload), [payload])
  const svg = useMemo(() => charmSvg(data), [data])
  const { pick } = data
  const title = petTitle(pick.pet, pick.nameIndex)
  const nickname = charmNickname(data.nickname).text
  const label = `${nickname}님의 ${pick.shiny ? '레어 ' : ''}부적. 수호펫 ${title}, 전투력 ${pick.power.toLocaleString('ko-KR')}, 오늘의 응원 "${CHEERS[pick.cheerIndex].join(' ')}"`
  const fileName = `iseonjwa-charm-${payload.k}.png`

  const [png, setPng] = useState<PngState>({ status: 'pending' })
  useEffect(() => {
    let cancelled = false
    let url: string | null = null
    renderCharmPng(data).then(
      (blob) => {
        if (cancelled) return
        url = URL.createObjectURL(blob)
        setPng({ status: 'ready', url, file: new File([blob], fileName, { type: 'image/png' }) })
      },
      () => {
        if (!cancelled) setPng({ status: 'failed' })
      },
    )
    return () => {
      cancelled = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [data, fileName])

  // Web Share API(파일 공유)는 HTTPS에서만 있다. 지금 배포 서버는 HTTP라 저장 버튼만 보이고, HTTPS가 붙으면 공유가 함께 보인다
  const canShare =
    png.status === 'ready' &&
    typeof navigator.canShare === 'function' &&
    navigator.canShare({ files: [png.file] })

  // 반짝이 부적은 손가락이나 마우스를 따라 빛이 움직인다
  const [glare, setGlare] = useState({ x: 50, y: 30 })
  const followPointer = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    setGlare({
      x: Math.round(((event.clientX - box.left) / box.width) * 100),
      y: Math.round(((event.clientY - box.top) / box.height) * 100),
    })
  }

  return (
    <>
      <h1 className="sr-only">발권 기념 {pick.shiny ? '레어' : '행운'} 부적</h1>
      <div
        className={cn('charm-card', pick.shiny && 'charm-card-shiny')}
        style={
          pick.shiny
            ? ({ '--glare-x': `${glare.x}%`, '--glare-y': `${glare.y}%` } as CSSProperties)
            : undefined
        }
        onPointerMove={pick.shiny ? followPointer : undefined}
      >
        {/* PNG가 준비되면 이미지로 바꾼다. 폰에서 길게 눌러 사진에 저장할 수 있게 한다 */}
        {png.status === 'ready' ? (
          <img src={png.url} alt={label} className="block h-full w-full" />
        ) : (
          // 우리가 만든 SVG다. 주소에서 온 글자(닉네임·공연 제목·좌석)는 charmSvg가 이스케이프한다
          <div
            role="img"
            aria-label={label}
            className="h-full w-full [&>svg]:h-full [&>svg]:w-full"
            dangerouslySetInnerHTML={{ __html: svg }}
          />
        )}
        {pick.shiny ? <span aria-hidden="true" className="charm-glare" /> : null}
      </div>

      <div className="grid w-full max-w-[420px] gap-2.5">
        {png.status === 'ready' ? (
          <Button asChild size="lg" className="bg-white text-primary-700 hover:bg-white/90">
            <a href={png.url} download={fileName}>
              <Download aria-hidden="true" />
              이미지 저장
            </a>
          </Button>
        ) : png.status === 'pending' ? (
          <Button size="lg" disabled aria-busy="true" className="bg-white/90 text-primary-700">
            이미지 만드는 중
          </Button>
        ) : (
          <p role="alert" className="type-body-sm rounded-2xl bg-white/10 px-4 py-3 text-center">
            이미지를 만들지 못했어요. 화면을 캡처해 저장해 주세요.
          </p>
        )}
        {canShare ? (
          <Button
            size="lg"
            variant="outline"
            className="border-white/40 bg-transparent text-white hover:bg-white/10"
            onClick={() => {
              void navigator
                .share({ files: [png.file], title: '이선좌 발권 기념 부적' })
                .catch(() => {
                  // 사용자가 공유 창을 닫으면 거절로 끝난다. 알릴 것이 없다
                })
            }}
          >
            <Share2 aria-hidden="true" />
            공유하기
          </Button>
        ) : null}
        <p className="type-caption text-center text-white/70">
          이미지를 길게 누르면 사진에 저장할 수 있어요. 인스타그램 스토리 크기(9:16)예요.
        </p>
        <Link
          to={paths.home}
          className="type-body-sm justify-self-center text-white/85 underline underline-offset-4"
        >
          이선좌에서 공연 둘러보기
        </Link>
      </div>
    </>
  )
}
