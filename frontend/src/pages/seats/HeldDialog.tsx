import { Check, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import type { HeldSeatHold } from '@/api/reservations'
import { GradeBadge } from '@/components/ticketing/GradeBadge'
import { TimeChip } from '@/components/ticketing/TimeChip'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import type { ServerClock } from '@/lib/serverClock'
import type { HeldSeat } from '@/auth/heldSeats'

interface HeldDialogProps {
  hold: HeldSeatHold
  seats: HeldSeat[]
  clock?: ServerClock
  onPay: () => void
  // 결제 가능 시간이 지난 뒤 [좌석 다시 고르기]
  onReselect: () => void
  // 대화상자가 내려간 뒤 초점을 둘 곳. Radix는 열기 전 초점(비활성이 된 [선택 완료])으로 돌려 body로 떨어진다
  onReturnFocus: () => void
}

// 좌석 확보 대화상자(샘플 .modal). 좌석은 이미 잡혔으니 닫는 길 없이 결제로만 보낸다.
// Note: 닫을 수 있으면 잡힌 좌석을 두고 좌석표로 돌아가 같은 회차를 또 고르게 된다(1인 매수 한도에 걸린다).
// open을 고정하고 onOpenChange를 주지 않아 Esc·바깥 누름으로 닫히지 않는다. 결제 가능 시간이 지나면 좌석이 풀리므로
// 그때만 [좌석 다시 고르기]로 화면이 대화상자를 내린다.
export function HeldDialog({
  hold,
  seats,
  clock,
  onPay,
  onReselect,
  onReturnFocus,
}: HeldDialogProps) {
  const [expired, setExpired] = useState(false)

  return (
    <Dialog open>
      <DialogContent
        showCloseButton={false}
        onCloseAutoFocus={(event) => {
          event.preventDefault()
          onReturnFocus()
        }}
        className="gap-4 px-6 pt-8 pb-6"
      >
        {expired ? (
          <span
            aria-hidden="true"
            className="mx-auto grid size-12 place-items-center rounded-full bg-warning-50 text-warning-700"
          >
            <TriangleAlert className="size-6" />
          </span>
        ) : (
          <span
            aria-hidden="true"
            className="mx-auto grid size-12 place-items-center rounded-full bg-success-50 text-success-600"
          >
            <Check className="size-6" strokeWidth={3} />
          </span>
        )}
        <DialogTitle className="text-center type-title text-gray-900">
          {expired ? '결제 가능 시간이 지났어요' : '좌석을 잡았어요'}
        </DialogTitle>
        <DialogDescription className="-mt-2 text-center type-body-sm text-gray-500">
          {expired
            ? '잡아 둔 좌석이 곧 풀려요. 좌석을 다시 골라 주세요.'
            : '아래 시간 안에 결제하면 예매가 확정돼요.'}
        </DialogDescription>
        <ul className="grid gap-2" aria-label="잡은 좌석">
          {seats.map((seat) => (
            <li
              key={seat.seatId}
              className="grid grid-cols-[auto_1fr] items-center gap-3 rounded-md bg-gray-50 p-3 type-body-sm"
            >
              <GradeBadge grade={seat.grade} />
              <span className="tabular-nums">
                {seat.section}구역{' '}
                <b className="font-semibold text-gray-900">
                  {seat.row}열 {seat.number}번
                </b>
              </span>
            </li>
          ))}
        </ul>
        <div className="justify-self-center">
          <TimeChip
            label="결제 가능"
            expiresAt={hold.paymentTokenExpiresAt}
            clock={clock}
            onExpire={() => setExpired(true)}
          />
        </div>
        {expired ? (
          <Button size="lg" className="w-full" onClick={onReselect}>
            좌석 다시 고르기
          </Button>
        ) : (
          <Button size="lg" className="w-full" onClick={onPay}>
            결제하러 가기
          </Button>
        )}
        <p className="text-center type-caption text-gray-500">
          결제하지 않으면 좌석은 자동으로 풀려요.
        </p>
      </DialogContent>
    </Dialog>
  )
}
