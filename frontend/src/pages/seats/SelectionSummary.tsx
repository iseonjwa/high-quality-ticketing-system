import { Loader2 } from 'lucide-react'
import { useRef } from 'react'
import { flushSync } from 'react-dom'
import { GradeBadge } from '@/components/ticketing/GradeBadge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatWon } from '@/lib/format'
import { seatLabel, type SelectedSeat } from '@/pages/seats/seatSections'
import type { HoldRequestState } from '@/pages/seats/useHoldRequest'

interface SelectionSummaryProps {
  seats: SelectedSeat[]
  quantity: number
  total: number
  request: HoldRequestState
  // 입장 시간이 끝났거나 좌석을 잡은 뒤라 더 고칠 수 없다
  locked: boolean
  onRemove: (seatId: number) => void
  onClear: () => void
  onSubmit: () => void
}

// 선택 요약(샘플 .summary). 앱 화면이 60rem 이상이면 좌석표 옆에 붙어 따라 내려온다
export function SelectionSummary({
  seats,
  quantity,
  total,
  request,
  locked,
  onRemove,
  onClear,
  onSubmit,
}: SelectionSummaryProps) {
  const need = quantity - seats.length
  const busy = request !== 'idle'
  const headingRef = useRef<HTMLHeadingElement>(null)
  const listRef = useRef<HTMLUListElement>(null)

  // [해제]를 누르면 그 버튼이 사라진다. 초점이 body로 떨어지지 않게 그 자리에 온 좌석의 [해제](없으면 앞 좌석,
  // 다 비면 제목)로 옮긴다
  function remove(seatId: number, index: number) {
    flushSync(() => onRemove(seatId))
    const buttons = listRef.current?.querySelectorAll('button') ?? []
    const next = buttons[Math.min(index, buttons.length - 1)]
    ;(next ?? headingRef.current)?.focus()
  }

  return (
    <aside
      aria-labelledby="selection-summary-title"
      className="@min-[60rem]:sticky @min-[60rem]:top-[88px]"
    >
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex items-baseline justify-between">
          <h2
            ref={headingRef}
            id="selection-summary-title"
            tabIndex={-1}
            className="type-heading text-gray-900 outline-none"
          >
            선택한 좌석
          </h2>
          <span className="type-body-sm text-gray-500 tabular-nums">
            <b className="text-gray-900">{seats.length}</b> / {quantity}매
          </span>
        </div>
        {seats.length === 0 ? (
          <p className="rounded-md border border-dashed border-gray-200 px-4 py-6 text-center type-body-sm text-gray-500">
            아직 고른 좌석이 없어요
          </p>
        ) : (
          <ul ref={listRef} className="grid gap-2">
            {seats.map((seat, index) => (
              <li
                key={seat.seatId}
                className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-md bg-gray-50 py-2 pr-1.5 pl-3 type-body-sm"
              >
                <GradeBadge grade={seat.grade} />
                <span className="min-w-0 tabular-nums">
                  {seat.section}구역{' '}
                  <b className="font-semibold text-gray-900">
                    {seat.row}열 {seat.number}번
                  </b>
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-gray-500"
                  disabled={busy || locked}
                  aria-label={`${seatLabel(seat)} 해제`}
                  onClick={() => remove(seat.seatId, index)}
                >
                  해제
                </Button>
              </li>
            ))}
          </ul>
        )}
        <dl className="flex items-baseline justify-between border-t border-gray-100 pt-4">
          <dt className="type-body-sm text-gray-500">합계</dt>
          <dd className="text-[22px] leading-[30px] font-extrabold text-gray-900 tabular-nums">
            {formatWon(total)}
          </dd>
        </dl>
        {request === 'retrying' ? (
          <div
            role="status"
            className="grid grid-cols-[auto_1fr] items-start gap-3 rounded-md bg-info-50 p-4 text-info-600"
          >
            <Loader2 aria-hidden="true" className="mt-0.5 size-[18px] motion-safe:animate-spin" />
            <div>
              <p className="type-body font-semibold">좌석을 확인하고 있어요</p>
              <p className="type-body-sm">
                연결이 잠시 끊겼어요. 같은 요청으로 결과를 확인하는 중이에요.
              </p>
            </div>
          </div>
        ) : (
          <Button
            size="lg"
            className="w-full"
            disabled={need !== 0 || locked}
            loading={request === 'sending'}
            onClick={onSubmit}
          >
            {need > 0 ? `${need}석 더 골라 주세요` : `${quantity}매 선택 완료`}
          </Button>
        )}
        <Button variant="outline" className="w-full" disabled={busy || locked} onClick={onClear}>
          다시 고르기
        </Button>
        <p className="type-caption text-gray-500">
          선택 완료를 누르면 좌석이 잠시 유지돼요. 그 안에 결제해 주세요.
        </p>
      </Card>
    </aside>
  )
}
