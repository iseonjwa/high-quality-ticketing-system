import { z } from 'zod'
import { apiRequest } from '@/api/client'

// 시연 장애 패널(ISE-134). Gateway의 운영자 전용 경로가 Mock PG의 장애 주입·원장 요약 API로 넘긴다(config-repo/gateway.yml).
// ADMIN만 쓴다. Mock PG 응답은 공통 응답 형식이 아니어서 envelope: false로 받는다(docs/design/payment/mock-pg.md 5·9절).
// Mock PG가 test·loadtest 프로필이 아니면(로컬 compose의 local) 이 API가 없어 404다.

export const faultTargetSchema = z.enum([
  'APPROVE',
  'PAYMENT_LOOKUP',
  'CANCEL',
  'REFUND',
  'REFUND_LOOKUP',
])
export type FaultTarget = z.infer<typeof faultTargetSchema>

export const FAULT_TARGETS = faultTargetSchema.options

// 서버가 더 많은 호출 종류·모드(변조형 등)를 돌려줘도 화면이 깨지지 않게 조회 응답은 문자열로 받는다
export const faultRuleSchema = z.object({
  target: z.string(),
  mode: z.string(),
  delayMs: z.number().int().nullish(),
  rate: z.number().nullish(),
  count: z.number().int().nullish(),
  orderId: z.string().nullish(),
  refundId: z.string().nullish(),
})
export type FaultRule = z.infer<typeof faultRuleSchema>

export const ledgerSummarySchema = z.object({
  approvedCount: z.number().int(),
  declinedCount: z.number().int(),
  canceledCount: z.number().int(),
  refundCount: z.number().int(),
  approvedTotal: z.number(),
  refundedTotal: z.number(),
  inFlightCount: z.number().int(),
  orderCount: z.number().int(),
})
export type LedgerSummary = z.infer<typeof ledgerSummarySchema>

export interface FaultRuleInput {
  target: FaultTarget
  mode: 'NONE' | 'DELAY' | 'DECLINE' | 'DROP_RESPONSE' | 'SERVER_ERROR'
  delayMs?: number
  // 생략하면 해제할 때까지 유지한다
  count?: number
}

export const demoFaultQueryKeys = {
  all: ['demoFaults'] as const,
  rules: ['demoFaults', 'rules'] as const,
  summary: ['demoFaults', 'summary'] as const,
}

// 지금 걸린 장애 설정. 호출 종류마다 하나씩 오고, 걸린 것이 없으면 NONE이다
export function getFaultRules() {
  return apiRequest({
    method: 'GET',
    path: '/admin/demo/pg/faults',
    schema: z.array(faultRuleSchema),
    envelope: false,
  })
}

export function applyFaultRule(rule: FaultRuleInput) {
  return apiRequest({
    method: 'POST',
    path: '/admin/demo/pg/faults',
    body: rule,
    schema: faultRuleSchema,
    envelope: false,
  })
}

export function getLedgerSummary() {
  return apiRequest({
    method: 'GET',
    path: '/admin/demo/pg/ledger/summary',
    schema: ledgerSummarySchema,
    envelope: false,
  })
}
