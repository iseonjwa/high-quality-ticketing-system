import { useCallback, useEffect, useState } from 'react'
import {
  INITIAL_QUEUE_ADMISSION_STATE,
  runQueueAdmission,
  type QueueAdmissionState,
} from '@/pages/queue/queueAdmission'

// 대기열 흐름을 화면에 잇는다. rejoin은 expired·notOpen·failed에서 처음부터 다시 줄 선다.
export function useQueueAdmission(
  scheduleId: number,
): QueueAdmissionState & { rejoin: () => void } {
  const [runCount, setRunCount] = useState(0)
  const [report, setReport] = useState<{ run: string; state: QueueAdmissionState } | null>(null)
  const run = `${scheduleId}:${runCount}`

  useEffect(() => {
    const controller = new AbortController()
    void runQueueAdmission({
      scheduleId,
      signal: controller.signal,
      onChange: (state) => setReport({ run, state }),
    })
    return () => controller.abort()
  }, [scheduleId, runCount, run])

  const rejoin = useCallback(() => setRunCount((count) => count + 1), [])

  // Note: 다시 시작하거나 회차가 바뀐 첫 렌더에 이전 실행의 상태가 비치지 않게 실행 표식이 맞을 때만 쓴다
  const state = report?.run === run ? report.state : INITIAL_QUEUE_ADMISSION_STATE
  return { ...state, rejoin }
}
