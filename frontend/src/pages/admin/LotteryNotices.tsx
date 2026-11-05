import { Link } from 'react-router'
import { DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW } from '@/api/adminLotteries'
import { isApiError } from '@/api/apiError'
import { paths } from '@/app/paths'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

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
  // Note: 409 응답에 기존 추첨 번호가 없고 운영자용 추첨 조회도 없다. 설정할 때 받은 번호로 여는 길만 알려 준다
  if (isApiError(error, 'LOTTERY_ALREADY_EXISTS')) {
    return (
      <Alert
        variant="warning"
        action={
          <Button asChild variant="outline" size="sm">
            <Link to={paths.admin}>운영으로</Link>
          </Button>
        }
      >
        <p className="font-semibold">이 회차에는 이미 추첨이 있어요</p>
        <p>
          회차마다 추첨은 하나예요. 이 화면에서는 그 추첨의 번호를 알 수 없어요. 설정할 때 받은
          번호를 운영 첫 화면의 "추첨 번호로 열기"에 넣어 주세요
        </p>
      </Alert>
    )
  }
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
