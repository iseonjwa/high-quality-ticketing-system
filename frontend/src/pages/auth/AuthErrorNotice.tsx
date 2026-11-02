import { isApiError } from '@/api/apiError'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { describeError } from '@/api/errorMessages'

// 입력 칸 하나로 가리킬 수 없는 실패(잠금, 연결 끊김, 예상하지 못한 오류)를 폼 위에 보인다.
export function AuthErrorNotice({ error }: { error: unknown }) {
  // 잠긴 계정은 화면에서 풀 수 없다. 운영자가 초기화하면 임시 비밀번호로 로그인해 바꾸는 길까지 알려 준다
  if (isApiError(error, 'ACCOUNT_LOCKED')) {
    return (
      <Alert variant="danger">
        <p>{describeError(error).message}</p>
        <p className="mt-1 type-caption">
          임시 비밀번호를 받으면 로그인할 때 새 비밀번호로 바꿀 수 있어요
        </p>
      </Alert>
    )
  }
  return <ErrorNotice error={error} />
}
