import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, RefreshCw } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { CLIENT_ERROR_CODES, isApiError } from '@/api/apiError'
import {
  applyFaultRule,
  demoFaultQueryKeys,
  FAULT_TARGETS,
  getFaultRules,
  getLedgerSummary,
  type FaultRule,
  type FaultRuleInput,
} from '@/api/demoFaults'
import { paths } from '@/app/paths'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatWon } from '@/lib/format'

// 시연 장애 패널(ISE-134). 운영자가 Mock PG에 장애를 걸어 결제 실패 흐름을 화면으로 보여 준다.
// 버튼은 다음 결제 한 건에만 걸리는 것(count 1)과 해제할 때까지 유지되는 것으로 나뉜다.
// 클릭 순서와 기대 화면은 docs/guides/demo-scenarios.md에 있다.

interface Scenario {
  id: string
  title: string
  description: string
  rule: FaultRuleInput
}

// Note: 결제 서비스의 PG 읽기 시한은 2초다(PgProperties). 지연은 그보다 넉넉히 길어야 "확인 중"이 확실히 난다
const APPROVE_DELAY_MS = 4_000

const SCENARIOS: Scenario[] = [
  {
    id: 'decline',
    title: '결제 거절',
    description: '다음 결제 1건을 카드사가 거절해요. 거절 안내가 뜨고 잡았던 좌석이 풀려요.',
    rule: { target: 'APPROVE', mode: 'DECLINE', count: 1 },
  },
  {
    id: 'delay',
    title: 'PG 응답 지연',
    description:
      '다음 승인 응답이 4초 늦어요. 결제 서비스가 2초에 기다림을 멈춰 "확인 중"이 뜨고, 10~20초 뒤 재확인이 승인을 찾아 확정해요.',
    rule: { target: 'APPROVE', mode: 'DELAY', delayMs: APPROVE_DELAY_MS, count: 1 },
  },
  {
    id: 'drop',
    title: '승인 응답 유실',
    description:
      'PG는 승인했는데 응답이 끊겨요. "확인 중"이 뜨고, 재조회로 승인을 확인해 확정해요. 돈은 한 번만 나가요.',
    rule: { target: 'APPROVE', mode: 'DROP_RESPONSE', count: 1 },
  },
  {
    id: 'server-error',
    title: 'PG 서버 오류 계속',
    description:
      '해제할 때까지 승인마다 500을 줘요. 결제는 "확인 중" 뒤 실패로 끝나요. 실패가 쌓이면 서킷 브레이커가 열려 PG를 부르지 않고 바로 실패해요.',
    rule: { target: 'APPROVE', mode: 'SERVER_ERROR' },
  },
]

const TARGET_LABEL: Record<string, string> = {
  APPROVE: '승인',
  PAYMENT_LOOKUP: '결제 조회',
  CANCEL: '늦은 승인 방지',
  REFUND: '환불',
  REFUND_LOOKUP: '환불 조회',
}

const MODE_LABEL: Record<string, string> = {
  DELAY: '응답 지연',
  DECLINE: '거절',
  DROP_RESPONSE: '응답 유실',
  PROCESSING: '처리 중 응답',
  SERVER_ERROR: '서버 오류',
  REJECT: '환불 거절',
}

function isActive(rule: FaultRule): boolean {
  return rule.mode !== 'NONE'
}

// Note: k6가 건 짧은 지연이나 주문 한정 규칙을 시나리오로 잘못 보이지 않게 지연·횟수 유무·한정까지 비교한다
function matches(rule: FaultRule, scenario: Scenario): boolean {
  const expected = scenario.rule
  return (
    rule.target === expected.target &&
    rule.mode === expected.mode &&
    (rule.delayMs ?? null) === (expected.delayMs ?? null) &&
    (rule.count == null) === (expected.count === undefined) &&
    rule.orderId == null &&
    rule.refundId == null &&
    (rule.rate == null || rule.rate === 1)
  )
}

// Mock PG가 test·loadtest 프로필이 아니면 장애 주입 API가 없어 Boot 기본 404(code 없음)다.
// Gateway에 이 경로가 없을 때 예매 서비스가 주는 공통 형식 404와 구분한다
function isInjectionClosed(error: unknown): boolean {
  return (
    isApiError(error) &&
    error.status === 404 &&
    error.code === CLIENT_ERROR_CODES.unexpectedResponse
  )
}

// 1건짜리 장애는 다른 창의 결제가 쓰고 원장도 바뀌므로, 패널을 띄워 둔 동안 주기적으로 다시 받는다
const REFRESH_MS = 3_000

export function AdminDemoFaultsPage() {
  const queryClient = useQueryClient()
  const rules = useQuery({
    queryKey: demoFaultQueryKeys.rules,
    queryFn: getFaultRules,
    refetchInterval: (query) => (isInjectionClosed(query.state.error) ? false : REFRESH_MS),
  })
  const summary = useQuery({
    queryKey: demoFaultQueryKeys.summary,
    queryFn: getLedgerSummary,
    refetchInterval: (query) => (isInjectionClosed(query.state.error) ? false : REFRESH_MS),
  })

  const apply = useMutation({
    mutationFn: (inputs: FaultRuleInput[]) => Promise.all(inputs.map(applyFaultRule)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: demoFaultQueryKeys.all }),
  })

  const closed = isInjectionClosed(rules.error)
  const activeRules = rules.data?.filter(isActive) ?? []

  function applyScenario(scenario: Scenario) {
    apply.mutate([scenario.rule], {
      onSuccess: () =>
        toast.success(`${scenario.title}을 걸었어요. 이제 다른 탭에서 결제해 보세요`),
    })
  }

  // 화면이 아는 규칙만이 아니라 모든 호출 종류를 푼다(다른 곳에서 건 규칙도 남기지 않는다)
  function clearAll() {
    const inputs = FAULT_TARGETS.map((target): FaultRuleInput => ({ target, mode: 'NONE' }))
    apply.mutate(inputs, { onSuccess: () => toast.success('장애를 모두 풀었어요') })
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: demoFaultQueryKeys.all })
  }

  return (
    <main className="mx-auto grid w-full max-w-[880px] gap-6">
      <Link
        to={paths.admin}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        운영
      </Link>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="type-title text-gray-900">시연 장애 패널</h1>
          <p className="type-body-sm text-gray-500">
            시연용 Mock PG에 장애를 걸어 결제가 실패하거나 늦어지는 흐름을 보여 줘요
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={refresh}>
          <RefreshCw aria-hidden="true" />
          새로 보기
        </Button>
      </div>

      {closed ? (
        <Alert variant="danger">
          <p className="font-semibold">이 환경에서는 장애를 걸 수 없어요</p>
          <p>
            Mock PG가 장애 주입을 열지 않은 프로필(local)로 떠 있어요. 배포 서버나 loadtest 프로필로
            띄운 Mock PG에서 써 주세요.
          </p>
        </Alert>
      ) : (
        <Alert variant="warning">
          <p className="font-semibold">이 서버의 모든 결제에 걸려요</p>
          <p>
            다른 사람이 먼저 결제하면 그 결제가 장애를 가져가요. 시연이 끝나면 [모두 해제]를 눌러
            주세요.
          </p>
        </Alert>
      )}

      {apply.isError ? <ErrorNotice error={apply.error} onClose={() => apply.reset()} /> : null}

      <section aria-labelledby="scenarios-title" className="grid gap-3">
        <h2 id="scenarios-title" className="type-heading text-gray-900">
          시나리오
        </h2>
        <ul className="grid gap-3 @min-[40rem]:grid-cols-2">
          {SCENARIOS.map((scenario) => {
            const active = rules.data?.some((rule) => matches(rule, scenario)) ?? false
            return (
              <li key={scenario.id}>
                <Card className="h-full gap-3 px-5 py-4">
                  <div className="flex items-center justify-between gap-2">
                    <h3 className="type-body font-semibold text-gray-900">{scenario.title}</h3>
                    {active ? <Badge variant="pending">걸려 있음</Badge> : null}
                  </div>
                  <p className="type-body-sm flex-1 text-gray-600">{scenario.description}</p>
                  <Button
                    variant={scenario.rule.count === undefined ? 'outline' : 'default'}
                    size="sm"
                    className="w-fit"
                    disabled={closed || rules.isPending || apply.isPending}
                    aria-busy={apply.isPending && apply.variables?.[0] === scenario.rule}
                    onClick={() => applyScenario(scenario)}
                  >
                    {scenario.rule.count === undefined
                      ? '해제할 때까지 걸기'
                      : '다음 결제 1건에 걸기'}
                  </Button>
                </Card>
              </li>
            )
          })}
        </ul>
      </section>

      <section aria-labelledby="active-title" className="grid gap-3">
        <div className="flex items-center justify-between gap-2">
          <h2 id="active-title" className="type-heading text-gray-900">
            지금 걸린 장애
          </h2>
          <Button
            variant="outline"
            size="sm"
            disabled={closed || rules.isPending || apply.isPending}
            onClick={clearAll}
          >
            모두 해제
          </Button>
        </div>
        <ActiveRules
          isPending={rules.isPending}
          error={closed ? null : rules.error}
          rules={activeRules}
          onRetry={() => void rules.refetch()}
        />
      </section>

      <section aria-labelledby="summary-title" className="grid gap-3">
        <h2 id="summary-title" className="type-heading text-gray-900">
          Mock PG 원장
        </h2>
        {summary.isPending ? (
          <Card className="px-5" role="status">
            <span className="sr-only">원장 요약을 불러오고 있어요</span>
            <Skeleton className="h-16 w-full" />
          </Card>
        ) : summary.isError ? (
          isInjectionClosed(summary.error) ? null : (
            <ErrorNotice error={summary.error} onRetry={() => void summary.refetch()} />
          )
        ) : (
          <Card className="px-5 py-4">
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 @min-[40rem]:grid-cols-4">
              <SummaryItem
                label="승인"
                value={`${summary.data.approvedCount}건`}
                sub={formatWon(summary.data.approvedTotal)}
              />
              <SummaryItem label="거절" value={`${summary.data.declinedCount}건`} />
              <SummaryItem
                label="환불"
                value={`${summary.data.refundCount}건`}
                sub={formatWon(summary.data.refundedTotal)}
              />
              <SummaryItem
                label="처리 중"
                value={`${summary.data.inFlightCount}건`}
                sub={`늦은 승인 방지 ${summary.data.canceledCount}건`}
              />
            </dl>
          </Card>
        )}
      </section>
    </main>
  )
}

function ActiveRules({
  isPending,
  error,
  rules,
  onRetry,
}: {
  isPending: boolean
  error: Error | null
  rules: FaultRule[]
  onRetry: () => void
}) {
  if (isPending) {
    return (
      <Card className="px-5" role="status">
        <span className="sr-only">걸린 장애를 불러오고 있어요</span>
        <Skeleton className="h-10 w-full" />
      </Card>
    )
  }
  if (error !== null) {
    return <ErrorNotice error={error} onRetry={onRetry} />
  }
  if (rules.length === 0) {
    return (
      <Card className="px-5 py-4">
        <p className="type-body-sm text-gray-500">걸린 장애가 없어요. 결제가 정상으로 돌아요.</p>
      </Card>
    )
  }
  return (
    <Card className="gap-0 py-0">
      <ul className="divide-y divide-gray-100">
        {rules.map((rule) => (
          <li key={rule.target} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3">
            <span className="type-body font-semibold text-gray-900">
              {TARGET_LABEL[rule.target] ?? rule.target} · {MODE_LABEL[rule.mode] ?? rule.mode}
            </span>
            <span className="type-body-sm text-gray-500 tabular-nums">
              {rule.count == null ? '해제할 때까지' : `남은 횟수 ${rule.count}`}
              {rule.delayMs != null ? ` · ${rule.delayMs / 1000}초 지연` : ''}
              {rule.rate != null && rule.rate < 1 ? ` · 확률 ${Math.round(rule.rate * 100)}%` : ''}
              {rule.orderId != null ? ` · 주문 ${rule.orderId}만` : ''}
              {rule.refundId != null ? ` · 환불 ${rule.refundId}만` : ''}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function SummaryItem({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="grid content-start gap-0.5">
      <dt className="type-caption text-gray-500">{label}</dt>
      <dd className="type-body font-semibold text-gray-900 tabular-nums">{value}</dd>
      {sub !== undefined ? (
        <dd className="type-caption text-gray-500 tabular-nums">{sub}</dd>
      ) : null}
    </div>
  )
}
