import { z } from 'zod'

// SA 11-1·11-2의 공통 형식. 성공과 실패는 모양이 달라서 잘못 감싼 응답을 받는 쪽에서 바로 알아챈다.

export const fieldErrorSchema = z.object({ field: z.string(), reason: z.string() })
export type FieldError = z.infer<typeof fieldErrorSchema>

export const failureEnvelopeSchema = z.object({
  status: z.number().int(),
  code: z.string(),
  message: z.string(),
  errors: z.array(fieldErrorSchema).optional(),
  traceId: z.string().nullable(),
})

// `data`는 API마다 달라 요청하는 쪽의 스키마로 따로 검증한다
export const successEnvelopeSchema = z.object({
  status: z.number().int(),
  message: z.string(),
  data: z.unknown(),
  traceId: z.string().nullable(),
})

// 응답 시각은 ISO 8601 `+09:00`이다(SA 11-1). 계약이 바뀌어 다른 형식이 오면 스키마 검증에서 드러난다.
export const isoDateTime = z.iso.datetime({ offset: true })

// 금액은 원 단위 정수다(SA 11-1).
export const won = z.number().int().nonnegative()

export const entityId = z.number().int().positive()

export function pageSchema<T extends z.ZodType>(itemSchema: T) {
  return z.object({
    content: z.array(itemSchema),
    page: z.number().int(),
    size: z.number().int(),
    totalElements: z.number().int(),
  })
}
