import { describeError, type ErrorNotice as Notice } from '@/api/errorMessages'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

// field는 입력 칸 아래에 쓰는 톤이라 화면 단위 알림에서는 위험으로 보인다
function alertVariant(tone: Notice['tone']) {
  return tone === 'field' ? 'danger' : tone
}

interface ErrorNoticeProps {
  error: unknown
  onRetry?: () => void
  onClose?: () => void
}

export function ErrorNotice({ error, onRetry, onClose }: ErrorNoticeProps) {
  const notice = describeError(error)
  return (
    <Alert
      variant={alertVariant(notice.tone)}
      onClose={onClose}
      action={
        onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        ) : undefined
      }
    >
      <p>{notice.message}</p>
      {notice.traceId ? (
        <p className="mt-1 type-caption text-gray-500">
          문의 번호 <span className="tabular-nums">{notice.traceId}</span>
        </p>
      ) : null}
    </Alert>
  )
}
