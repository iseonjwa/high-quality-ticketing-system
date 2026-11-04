import { DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW } from '@/api/adminLotteries'
import { isApiError } from '@/api/apiError'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'

// 설정·수정 응답의 경고(warnings). 막지 않는 안내라 정보 알림으로 보인다. 모르는 경고는 코드 그대로 보인다
export function LotteryWarnings({ warnings }: { warnings: string[] }) {
  if (warnings.length === 0) {
    return null
  }
  return (
    <Alert variant="info">
      <ul className="grid gap-1">
        {warnings.map((warning) => (
          <li key={warning}>
            {warning === DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW
              ? '추첨 시각이 새벽 3~5시 밖이에요. 추첨 직후 자동결제가 몰리므로 일반 예매가 적은 새벽을 권해요'
              : `서버 경고: ${warning}`}
          </li>
        ))}
      </ul>
    </Alert>
  )
}

// 설정·수정 화면이 같이 쓰는 오류 안내. 값 사이 규칙 위반(칸별 오류 없는 400)은 규칙을 다시 알려 준다
export function LotteryFormError({ error }: { error: unknown }) {
  if (isApiError(error, 'VALIDATION_FAILED')) {
    return (
      <Alert variant="warning">
        입력값 사이 규칙이 맞지 않아요. 응모 시작 &lt; 응모 마감 ≤ 추첨 시각이고, 추첨은 공연 시작
        1시간 전까지여야 해요
      </Alert>
    )
  }
  return <ErrorNotice error={error} />
}
