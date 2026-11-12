import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { authQueryKeys, getMe } from '@/api/auth'
import type { ReservationDetail } from '@/api/reservations'
import { paths } from '@/app/paths'
import { QrCode } from '@/components/ticketing/QrCode'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { charmHash, charmUrl } from '@/pages/charm/charmLink'
import type { ReservationHeading } from '@/pages/reservations/reservationNavigation'
import { seatLabel } from '@/pages/seats/seatSections'

// 예매 상세의 발권 이벤트 카드(ISE-257). 확정 예매의 티켓마다 부적 QR을 보인다.
// 넓은 화면에서는 노트북 화면을 폰으로 찍는 경우가 많아 QR을 키운다(한 칸이 2px 아래로 내려가지 않게).
// 부적 그림 코드는 QR을 연 화면(CharmPage)에만 있고, 여기서는 주소만 만든다(예매 화면 묶음을 키우지 않는다).

// 닉네임은 자주 바뀌지 않는다. 마이페이지와 같은 조회 키를 써서 이미 받은 값을 그대로 쓴다
const ME_STALE_MILLIS = 5 * 60 * 1000

export function CharmQrCard({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe, staleTime: ME_STALE_MILLIS })
  const ticketBySeat = new Map(detail.tickets.map((ticket) => [ticket.seatId, ticket.ticketNo]))
  const tickets = detail.seats.flatMap((seat) => {
    const ticketNo = ticketBySeat.get(seat.seatId)
    return ticketNo ? [{ seat, ticketNo }] : []
  })
  if (tickets.length === 0) {
    return null
  }
  // 닉네임을 못 받아도 부적은 준다. 받는 중에는 QR 자리를 비워 둔다(받은 뒤 QR이 바뀌면 이미 찍은 사람과 달라진다)
  const nickname = me.data?.nickname ?? (me.isError ? '관객' : null)

  return (
    <section aria-labelledby="charm-title">
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="grid gap-1">
          <h2 id="charm-title" className="type-heading text-gray-900">
            발권 기념 행운 부적
          </h2>
          <p className="type-body-sm text-gray-600">
            폰 카메라로 QR을 찍으면 나만의 수호펫과 응원 한 줄이 담긴 부적을 받을 수 있어요.
            인스타그램 스토리 크기(9:16) 이미지로 저장돼요.
          </p>
        </div>
        <ul className="grid gap-3 @min-[40rem]:grid-cols-2">
          {tickets.map(({ seat, ticketNo }, index) => {
            const seatText = `${seat.grade}석 · ${seatLabel(seat)}`
            const hash =
              nickname === null
                ? null
                : charmHash({
                    r: detail.reservationId,
                    i: index,
                    n: nickname,
                    t: heading?.title,
                    s: heading?.startsAt,
                    z: seatText,
                    k: ticketNo,
                  })
            return (
              <li key={ticketNo} className="flex items-center gap-4 rounded-2xl bg-gray-50 p-3">
                {hash === null ? (
                  <Skeleton className="size-[132px] shrink-0 rounded-xl @min-[40rem]:size-[168px]" />
                ) : (
                  <QrCode
                    value={charmUrl(window.location.origin, hash)}
                    label={`${seatText} 부적 QR`}
                    className="size-[132px] shrink-0 rounded-xl @min-[40rem]:size-[168px]"
                  />
                )}
                <div className="grid min-w-0 justify-items-start gap-2">
                  <p className="type-body-sm font-semibold break-keep text-gray-900">{seatText}</p>
                  {hash === null ? (
                    <Button variant="outline" size="sm" disabled>
                      이 기기에서 열기
                    </Button>
                  ) : (
                    <Button asChild variant="outline" size="sm">
                      <Link to={{ pathname: paths.charm, hash }}>이 기기에서 열기</Link>
                    </Button>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      </Card>
    </section>
  )
}
