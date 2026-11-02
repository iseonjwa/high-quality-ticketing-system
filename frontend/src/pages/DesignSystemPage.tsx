import { useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/apiError'
import type { ScheduleSeats } from '@/api/concerts'
import { GradeBadge } from '@/components/ticketing/GradeBadge'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Countdown } from '@/components/ticketing/Countdown'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { PendingStatus } from '@/components/ticketing/PendingStatus'
import { QueuePosition } from '@/components/ticketing/QueuePosition'
import { SeatMap } from '@/components/ticketing/SeatMap'
import { StepIndicator } from '@/components/ticketing/StepIndicator'
import { TimeChip } from '@/components/ticketing/TimeChip'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

// 개발 서버에서만 붙는 확인 화면(`/_design`)이다. 운영 빌드에는 들어가지 않는다.

function seatRows(sectionPrefix: number, rows: number, perRow: number, unavailable: number[]) {
  return Array.from({ length: rows * perRow }, (_, index) => {
    const row = Math.floor(index / perRow) + 1
    const number = (index % perRow) + 1
    const seatId = sectionPrefix * 1000 + index + 1
    return { seatId, row, number, available: !unavailable.includes(seatId) }
  })
}

const SECTIONS: ScheduleSeats['sections'] = [
  { section: 'A', grade: 'VIP', price: 165000, seats: seatRows(1, 3, 8, [1003, 1004, 1013]) },
  { section: 'B', grade: 'R', price: 132000, seats: seatRows(2, 3, 8, [2005, 2006]) },
]
// 추첨석과 방금 선택됨(409) 표시를 눈으로 볼 수 있게 고정해 둔다
const LOTTERY_SEAT_IDS = [2001, 2002, 2009, 2010]
const JUST_TAKEN_SEAT_IDS = [1008]

// 만료 시각을 고정해 세 상태를 동시에 본다. 새로고침하면 다시 시작한다.
const NOW = Date.now()
const iso = (offsetMillis: number) => new Date(NOW + offsetMillis).toISOString()

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="type-heading">{title}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

function Row({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-3">{children}</div>
}

export function DesignSystemPage() {
  const [quantity, setQuantity] = useState('2')
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([])
  const [showAlert, setShowAlert] = useState(true)

  return (
    <main className="flex flex-col gap-6">
      <h1 className="type-title text-gray-900">디자인 시스템 확인 화면</h1>
      <p className="type-body-sm text-gray-500">
        개발 서버에서만 보이는 화면이다. 상태별 컴포넌트를 한 번에 늘어놓는다.
      </p>

      <Section title="버튼">
        <Row>
          <Button>주 버튼</Button>
          <Button variant="outline">보조</Button>
          <Button variant="destructive">위험</Button>
          <Button variant="ghost">고스트</Button>
          <Button variant="link">링크</Button>
        </Row>
        <Row>
          <Button size="lg">큰 버튼 52px</Button>
          <Button loading>처리 중</Button>
          <Button disabled>비활성</Button>
          <Button variant="outline" disabled>
            보조 비활성
          </Button>
        </Row>
      </Section>

      <Section title="입력·선택·세그먼트">
        <div className="grid max-w-sm gap-4">
          <div className="grid gap-2">
            <Label htmlFor="ds-email">기본</Label>
            <Input id="ds-email" placeholder="이메일" />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="ds-error">오류</Label>
            <Input
              id="ds-error"
              aria-invalid="true"
              aria-describedby="ds-error-text"
              defaultValue="abc"
            />
            <p id="ds-error-text" className="type-caption text-danger-600">
              이메일 형식을 확인해 주세요
            </p>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="ds-disabled">비활성</Label>
            <Input id="ds-disabled" disabled defaultValue="바꿀 수 없어요" />
          </div>
          <Select defaultValue="vip">
            <SelectTrigger aria-label="등급">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="vip">VIP</SelectItem>
              <SelectItem value="r">R</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <ToggleGroup
          type="single"
          value={quantity}
          onValueChange={(value) => value && setQuantity(value)}
          aria-label="매수"
        >
          {['1', '2', '3', '4'].map((value) => (
            <ToggleGroupItem key={value} value={value}>
              {value}매
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </Section>

      <Section title="배지">
        <Row>
          <Badge variant="onSale">예매 중</Badge>
          <Badge variant="upcoming">오픈 예정</Badge>
          <Badge variant="soldOut">매진</Badge>
          <Badge variant="lottery">추첨</Badge>
        </Row>
        <Row>
          <SaleStatusBadge status="BEFORE_OPEN" />
          <SaleStatusBadge status="ON_SALE" />
          <SaleStatusBadge status="CLOSED" />
        </Row>
        <Row>
          <GradeBadge grade="VIP" />
          <GradeBadge grade="R" />
          <GradeBadge grade="S" />
          <GradeBadge grade="A" />
          <GradeBadge grade="새등급" />
        </Row>
      </Section>

      <Section title="알림·토스트·모달·스켈레톤">
        <Alert variant="info">
          <AlertTitle>정보</AlertTitle>
          <AlertDescription>처리 중이거나 확인 중일 때 쓴다.</AlertDescription>
        </Alert>
        <Alert variant="success">좌석을 잡았어요.</Alert>
        <Alert
          variant="warning"
          action={
            <Button size="sm" variant="outline">
              행동
            </Button>
          }
        >
          이미 선택된 좌석입니다. 남은 좌석은 그대로 두었어요
        </Alert>
        {showAlert ? (
          <Alert variant="danger" onClose={() => setShowAlert(false)}>
            입장 시간이 끝났어요
          </Alert>
        ) : (
          <Button variant="outline" onClick={() => setShowAlert(true)}>
            위험 알림 다시 보기
          </Button>
        )}
        <Row>
          <Button variant="outline" onClick={() => toast('먼저 해제해 주세요')}>
            토스트 띄우기
          </Button>
          <Dialog>
            <DialogTrigger asChild>
              <Button variant="outline">모달 열기</Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>좌석을 잡았어요</DialogTitle>
                <DialogDescription>결제 가능 시간 안에 결제를 마쳐 주세요.</DialogDescription>
              </DialogHeader>
              <DialogFooter>
                <Button>결제하러 가기</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </Row>
        <div className="grid gap-2">
          <Skeleton className="h-6 w-1/3" />
          <Skeleton className="h-20 w-full" />
        </div>
      </Section>

      <Section title="단계·시간·대기·확인 중">
        <StepIndicator currentIndex={1} />
        <Row>
          <TimeChip label="입장 유효 시간" expiresAt={iso(5 * 60_000)} />
          <TimeChip label="경고" expiresAt={iso(45_000)} />
          <TimeChip label="만료" expiresAt={iso(-1000)} />
        </Row>
        <Countdown targetAt={iso(2 * 86_400_000 + 3_845_000)} serverTime={iso(0)} />
        <QueuePosition position={15234} estimatedWaitSeconds={300} initialPosition={20000} />
        <PendingStatus
          title="결제를 확인하고 있어요"
          description="창을 닫지 말고 잠시만 기다려 주세요."
        />
      </Section>

      <Section title="빈 상태·에러 안내">
        <EmptyState
          title="아직 예매한 공연이 없어요"
          description="공연 목록에서 마음에 드는 공연을 골라 보세요."
          action={<Button variant="outline">공연 보러 가기</Button>}
        />
        <ErrorNotice
          error={new ApiError({ status: 409, code: 'SEAT_ALREADY_HELD', message: '' })}
          onClose={() => undefined}
        />
        <ErrorNotice
          error={
            new ApiError({ status: 500, code: 'UNKNOWN', message: '', traceId: 'trace-demo-0001' })
          }
          onRetry={() => toast('다시 시도')}
        />
      </Section>

      <Section title="좌석표 (최대 4석)">
        <p className="type-body-sm text-gray-500">
          선택 {selectedSeatIds.length}석. A구역 1열 3·4번은 선택할 수 없음, B구역 일부는 추첨석,
          A구역 2열 8번은 방금 선택됨 표시다.
        </p>
        <SeatMap
          sections={SECTIONS}
          selectedSeatIds={selectedSeatIds}
          maxSelectable={4}
          onChange={setSelectedSeatIds}
          onLimitReached={() => toast('먼저 해제해 주세요')}
          lotterySeatIds={LOTTERY_SEAT_IDS}
          justTakenSeatIds={JUST_TAKEN_SEAT_IDS}
        />
      </Section>
    </main>
  )
}
