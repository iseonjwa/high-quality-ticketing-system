import { http, HttpResponse } from 'msw'
import { requireAdmin } from '@/mocks/handlers/admin'
import { readBody } from '@/mocks/respond'

// 시연 장애 패널이 Gateway로 부르는 Mock PG API(mock-pg FaultController·LedgerController). 공통 응답 형식이 아니다.
// 목에서는 설정을 기억해 보여 주기만 한다. 목 결제 흐름은 이 설정을 따르지 않는다(결제 시나리오는 ?mock=으로 고른다).

const TARGETS = ['APPROVE', 'PAYMENT_LOOKUP', 'CANCEL', 'REFUND', 'REFUND_LOOKUP'] as const
type Target = (typeof TARGETS)[number]

interface MockFaultRule {
  target: Target
  mode: string
  delayMs: number | null
  rate: number | null
  count: number | null
  orderId: null
  refundId: null
}

const MODES_BY_TARGET: Record<Target, string[]> = {
  APPROVE: ['NONE', 'DELAY', 'DECLINE', 'DROP_RESPONSE', 'PROCESSING', 'SERVER_ERROR'],
  PAYMENT_LOOKUP: ['NONE', 'DELAY', 'PROCESSING', 'SERVER_ERROR'],
  CANCEL: ['NONE', 'DELAY', 'DROP_RESPONSE', 'PROCESSING', 'SERVER_ERROR'],
  REFUND: ['NONE', 'DELAY', 'DROP_RESPONSE', 'PROCESSING', 'SERVER_ERROR', 'REJECT'],
  REFUND_LOOKUP: ['NONE', 'DELAY', 'PROCESSING', 'SERVER_ERROR'],
}

function noneRule(target: Target): MockFaultRule {
  return {
    target,
    mode: 'NONE',
    delayMs: null,
    rate: null,
    count: null,
    orderId: null,
    refundId: null,
  }
}

const rules = new Map<Target, MockFaultRule>(TARGETS.map((target) => [target, noneRule(target)]))

// Mock PG는 공통 실패 형식이 아니라 `{code}`만 준다
function invalid() {
  return HttpResponse.json({ code: 'INVALID_REQUEST' }, { status: 400 })
}

function isTarget(value: unknown): value is Target {
  return typeof value === 'string' && (TARGETS as readonly string[]).includes(value)
}

export const demoFaultHandlers = [
  http.get('/api/v1/admin/demo/pg/faults', ({ request }) => {
    requireAdmin(request)
    return HttpResponse.json([...rules.values()])
  }),

  http.post('/api/v1/admin/demo/pg/faults', async ({ request }) => {
    requireAdmin(request)
    const body = await readBody(request)
    const { target, mode, delayMs, count } = body
    if (!isTarget(target) || typeof mode !== 'string' || !MODES_BY_TARGET[target].includes(mode)) {
      return invalid()
    }
    if (mode === 'DELAY' && (typeof delayMs !== 'number' || delayMs < 1 || delayMs > 60_000)) {
      return invalid()
    }
    if (count !== undefined && (typeof count !== 'number' || count < 1)) {
      return invalid()
    }
    const rule: MockFaultRule = {
      ...noneRule(target),
      mode,
      delayMs: mode === 'DELAY' ? (delayMs as number) : null,
      count: mode === 'NONE' ? null : ((count as number | undefined) ?? null),
    }
    rules.set(target, rule)
    return HttpResponse.json(rule)
  }),

  http.get('/api/v1/admin/demo/pg/ledger/summary', ({ request }) => {
    requireAdmin(request)
    return HttpResponse.json({
      approvedCount: 12,
      declinedCount: 2,
      canceledCount: 1,
      refundCount: 1,
      approvedTotal: 1_980_000,
      refundedTotal: 165_000,
      inFlightCount: 0,
      orderCount: 15,
    })
  }),
]
