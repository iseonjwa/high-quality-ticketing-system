import { z } from 'zod'
import { apiRequest } from '@/api/client'

// 내부 AI 상담(SA F8, docs/design/assistant/README.md 2절). 로그인한 사용자만 쓴다.
// LLM이 꺼져 있거나 장애면 오류 대신 mode SEARCH(검색 결과와 출처만)로 온다.

export const QUESTION_MAX_LENGTH = 500

export const assistantSourceSchema = z.object({
  id: z.string(),
  title: z.string(),
  excerpt: z.string(),
})
export type AssistantSource = z.infer<typeof assistantSourceSchema>

export const assistantAnswerSchema = z.object({
  answer: z.string(),
  mode: z.enum(['AI', 'SEARCH']),
  fallbackReason: z.enum(['LLM_DISABLED', 'LLM_UNAVAILABLE']).nullable(),
  sources: z.array(assistantSourceSchema),
})
export type AssistantAnswer = z.infer<typeof assistantAnswerSchema>

export function askAssistant(question: string) {
  return apiRequest({
    method: 'POST',
    path: '/assistant/messages',
    body: { question },
    schema: assistantAnswerSchema,
  })
}
