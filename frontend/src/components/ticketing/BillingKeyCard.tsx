import { useMutation } from '@tanstack/react-query'
import { useId, useRef, useState, type MouseEvent } from 'react'
import { toast } from 'sonner'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { registerBillingKey, revokeBillingKey } from '@/api/billingKeys'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import {
  removeBillingKeyRecord,
  saveBillingKeyRecord,
  useBillingKeyRecord,
} from '@/auth/billingKeyRecord'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from '@/components/ui/dialog'

// 자동결제 카드(빌링키) 카드. 내 정보와 추첨 응모 화면에 둔다.
// 서버에 보유 여부를 묻는 API가 없어 이 브라우저에서 등록한 기록(billingKeyRecord)으로 보인다.
// 기록이 없으면 해지 버튼을 두지 않는다. 다른 브라우저에서 등록한 카드가 서버에 남아 있어도 다시 등록하면
// 앞 카드가 해지되고 새 카드로 바뀐다(ISE-186). 등록 결과를 모를 때만 안내에 해지를 둔다(RegisterError)

// 시연 결제(Mock PG)에 보낼 인증 값. 실제 PG라면 결제창이 준다(ISE-125).
// Note: crypto.randomUUID()는 http 배포 주소에서 없다(newIdempotencyKey 참고)
function demoAuthKey(): string {
  return `demo-${newIdempotencyKey()}`
}

export function BillingKeyCard({
  userId,
  titleAs = 'h2',
  anchorId,
}: {
  userId: number
  titleAs?: 'h2' | 'h3'
  // 다른 곳에서 이 카드로 초점을 옮길 때 쓰는 영역 id(추첨 응모 화면의 "카드 등록" 안내)
  anchorId?: string
}) {
  const Title = titleAs
  const titleId = useId()
  const record = useBillingKeyRecord(userId)
  const [confirmOpen, setConfirmOpen] = useState(false)
  // 대화상자를 닫으면 연 버튼으로 초점을 돌린다. 해지로 그 버튼이 사라졌으면 등록 버튼으로 간다
  const openerRef = useRef<HTMLElement | null>(null)
  const registerButtonRef = useRef<HTMLButtonElement>(null)

  const register = useMutation({
    mutationFn: () => registerBillingKey(demoAuthKey()),
    onSuccess: (key) => {
      saveBillingKeyRecord(userId, {
        cardLast4: key.cardLast4,
        registeredAt: new Date().toISOString(),
      })
      toast('자동결제 카드를 등록했어요')
    },
  })
  const revoke = useMutation({
    mutationFn: revokeBillingKey,
    onSuccess: ({ revoked }) => {
      removeBillingKeyRecord(userId)
      // 앞서 실패한 등록 안내("다시 등록하면 새 카드로 바뀌어요" 등)는 해지 뒤에 맞지 않는다
      register.reset()
      setConfirmOpen(false)
      toast(revoked ? '자동결제 카드를 해지했어요' : '등록된 카드가 없었어요')
    },
  })
  const busy = register.isPending || revoke.isPending

  const openRevoke = (event: MouseEvent<HTMLElement>) => {
    openerRef.current = event.currentTarget
    revoke.reset()
    setConfirmOpen(true)
  }

  return (
    <section
      id={anchorId}
      tabIndex={anchorId === undefined ? undefined : -1}
      aria-labelledby={titleId}
      className="scroll-mt-24 outline-none"
    >
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Title id={titleId} className="type-heading text-gray-900">
            자동결제 카드
          </Title>
          {record ? (
            <Badge variant="onSale">등록됨</Badge>
          ) : (
            <Badge variant="soldOut">기록 없음</Badge>
          )}
        </div>
        {register.isError ? (
          <RegisterError error={register.error} busy={busy} onRevoke={openRevoke} />
        ) : null}
        {record ? (
          <div className="grid grid-cols-[auto_1fr_auto] items-center gap-4 rounded-md bg-gray-50 p-4">
            <span
              aria-hidden="true"
              className="relative h-[30px] w-11 rounded-[6px] bg-linear-135 from-gray-700 to-gray-900 after:absolute after:top-2 after:left-1.5 after:h-2 after:w-2.5 after:rounded-[2px] after:bg-[#e9c46a]"
            />
            <div className="min-w-0">
              <p className="type-body font-semibold text-gray-900">시연용 카드</p>
              <p className="type-body-sm text-gray-500 tabular-nums">
                {record.cardLast4 ? `•••• ${record.cardLast4} · ` : ''}
                {formatDate(record.registeredAt)} 등록
              </p>
            </div>
            <Button variant="outline" disabled={busy} onClick={openRevoke}>
              해지
            </Button>
          </div>
        ) : (
          <div className="rounded-md border border-dashed border-gray-200 px-4 py-5 text-center">
            <p className="type-body-sm text-gray-500">이 브라우저에서 등록한 카드가 없어요</p>
          </div>
        )}
        <Button
          ref={registerButtonRef}
          variant={record ? 'outline' : 'default'}
          className="w-full"
          loading={register.isPending}
          disabled={revoke.isPending}
          onClick={() => register.mutate()}
        >
          {record ? '다른 카드로 바꾸기' : '카드 등록'}
        </Button>
        <p className="type-caption text-gray-500">
          추첨에 당첨되면 이 카드로 자동결제돼요. 다시 등록하면 앞 카드는 해지되고 새 카드로
          바뀌어요. 시연용 결제라 카드 정보를 받지 않아요.
        </p>
      </Card>
      <Dialog
        open={confirmOpen}
        onOpenChange={(open) => {
          if (!revoke.isPending) {
            setConfirmOpen(open)
          }
        }}
      >
        <DialogContent
          showCloseButton={!revoke.isPending}
          onCloseAutoFocus={(event) => {
            // 트리거 없이 연 대화상자라 Radix가 초점을 돌려줄 곳을 모른다. 그대로 두면 body로 간다
            event.preventDefault()
            const opener = openerRef.current
            if (opener?.isConnected) {
              opener.focus()
            } else {
              registerButtonRef.current?.focus()
            }
          }}
        >
          <DialogTitle className="type-heading text-gray-900">
            자동결제 카드를 해지할까요?
          </DialogTitle>
          <DialogDescription className="type-body-sm text-gray-500">
            응모한 추첨에 당첨되면 결제되지 않아 당첨이 다음 순번으로 넘어가요.
          </DialogDescription>
          {/* 실패하면 대화상자가 열린 채 남는다. 카드에 두면 모달 뒤에 가려져 읽히지 않는다 */}
          {revoke.isError ? <ErrorNotice error={revoke.error} /> : null}
          <DialogFooter>
            <Button
              variant="outline"
              disabled={revoke.isPending}
              onClick={() => setConfirmOpen(false)}
            >
              취소
            </Button>
            <Button
              variant="destructive"
              loading={revoke.isPending}
              onClick={() => revoke.mutate()}
            >
              해지
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  )
}

function RegisterError({
  error,
  busy,
  onRevoke,
}: {
  error: unknown
  busy: boolean
  onRevoke: (event: MouseEvent<HTMLElement>) => void
}) {
  if (isApiError(error, 'BILLING_KEY_REJECTED')) {
    return (
      <Alert variant="danger">
        <p className="font-semibold">카드사가 등록을 거절했어요</p>
        <p>다른 카드로 다시 등록해 주세요.</p>
      </Alert>
    )
  }
  if (isApiError(error, 'PG_UNAVAILABLE')) {
    // 서버는 결제사 결과를 모르면 저장하지 않고 앞 카드도 그대로 둔다(BillingKeyService). 그래서 "등록 안 됨"이 맞다
    return (
      <Alert variant="warning">
        <p className="font-semibold">카드사 연결이 원활하지 않아요</p>
        <p>잠시 뒤 다시 등록해 주세요.</p>
      </Alert>
    )
  }
  if (isOutcomeUnknown(error)) {
    // 멱등키가 없지만 다시 등록하면 새 카드로 바뀔 뿐이라(앞 카드 해지) 그대로 다시 누르게 한다.
    // 서버에서는 등록됐을 수 있어 쓰지 않으려면 해지하게 한다(해지는 카드가 없어도 성공한다)
    return (
      <Alert
        variant="warning"
        action={
          <Button variant="outline" size="sm" disabled={busy} onClick={onRevoke}>
            해지
          </Button>
        }
      >
        <p className="font-semibold">등록됐는지 확인하지 못했어요</p>
        <p>다시 등록하면 새 카드로 바뀌어요. 쓰지 않으려면 해지해 주세요.</p>
      </Alert>
    )
  }
  return <ErrorNotice error={error} />
}

const monthDay = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: '2-digit',
  day: '2-digit',
})

// "11.04"처럼 보인다
function formatDate(iso: string): string {
  const parts = Object.fromEntries(
    monthDay.formatToParts(new Date(iso)).map((part) => [part.type, part.value]),
  )
  return `${parts.month}.${parts.day}`
}
