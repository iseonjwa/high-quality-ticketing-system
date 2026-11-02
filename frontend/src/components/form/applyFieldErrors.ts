import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { isApiError } from '@/api/apiError'
import { ERROR_NOTICES } from '@/api/errorMessages'

// 서버 검증 실패(400 VALIDATION_FAILED)의 칸별 오류를 입력 칸 아래 문구로 옮긴다. 옮긴 칸이 있으면 true다.
// 화면 검사가 서버 규칙을 따르므로 보통은 오지 않는다. 서버 문구(reason)는 Bean Validation 기본 문구라
// 문구 규칙(design-system.md 1절)에 맞지 않아 보이지 않고 공통 안내를 쓴다.
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!isApiError(error, 'VALIDATION_FAILED')) {
    return false
  }
  const known: readonly string[] = fields
  const matched = error.fieldErrors
    .map((fieldError) => fieldError.field)
    .filter((field): field is Path<T> => known.includes(field))
  matched.forEach((field, index) => {
    setError(
      field,
      { type: 'server', message: ERROR_NOTICES.VALIDATION_FAILED.message },
      { shouldFocus: index === 0 },
    )
  })
  return matched.length > 0
}
