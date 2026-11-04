import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { setMockScenario } from '@/mocks/scenario'
import { useQueueAdmission } from '@/pages/queue/useQueueAdmission'
import { ON_SALE_SCHEDULE_ID, recordRequests, signInAsDemo } from '@/test/api'

beforeEach(async () => {
  await signInAsDemo()
})

describe('useQueueAdmission', () => {
  it('처음에는 joining이고 흐름이 끝나면 admitted가 된다', async () => {
    setMockScenario({ queue: 'ready' })

    const { result } = renderHook(() => useQueueAdmission(ON_SALE_SCHEDULE_ID))

    expect(result.current.step).toBe('joining')
    await waitFor(() => expect(result.current.step).toBe('admitted'))
  })

  it('만료된 뒤 rejoin하면 처음부터 다시 돌아 입장한다', async () => {
    setMockScenario({ queue: 'expired' })
    const { result } = renderHook(() => useQueueAdmission(ON_SALE_SCHEDULE_ID))
    await waitFor(() => expect(result.current.step).toBe('expired'), { timeout: 5000 })

    act(() => result.current.rejoin())

    expect(result.current.step).toBe('joining')
    await waitFor(() => expect(result.current.step).toBe('admitted'), { timeout: 5000 })
  })

  it('언마운트하면 흐름을 멈춰 요청이 더 가지 않는다', async () => {
    const requests = recordRequests(`/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/queue/me`)
    const { result, unmount } = renderHook(() => useQueueAdmission(ON_SALE_SCHEDULE_ID))
    await waitFor(() => expect(result.current.step).toBe('waiting'))

    unmount()
    await new Promise((resolve) => setTimeout(resolve, 1300))

    expect(requests).toHaveLength(0)
  })
})
