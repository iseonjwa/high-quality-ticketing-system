import { describe, expect, it } from 'vitest'
import { requestLogDashboardUrl } from '@/demo/grafana'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'
const STARTED_AT = 1_800_000_000_000
const FROM = STARTED_AT - 900_000
const TO = STARTED_AT + 900_000

describe('requestLogDashboardUrl', () => {
  it('traceId와 요청 시각 앞뒤 15분으로 대시보드 주소를 만든다', () => {
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'http://localhost:3000')).toBe(
      `http://localhost:3000/d/iseonjwa-request-logs?orgId=1&var-traceId=${TRACE_ID}&from=${FROM}&to=${TO}`,
    )
  })

  it('기준 주소의 하위 경로와 끝 슬래시를 처리한다', () => {
    const expected = `http://host/grafana/d/iseonjwa-request-logs?orgId=1&var-traceId=${TRACE_ID}&from=${FROM}&to=${TO}`

    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'http://host/grafana/')).toBe(expected)
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'http://host/grafana')).toBe(expected)
  })

  it('기준 주소를 주지 않으면 기본 주소를 쓴다', () => {
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT)).toMatch(/^http:\/\/localhost:3000\/d\//)
  })

  it('잘못된 traceId나 기준 주소는 null이다', () => {
    expect(requestLogDashboardUrl('abc', STARTED_AT, 'http://localhost:3000')).toBeNull()
    expect(requestLogDashboardUrl('X'.repeat(32), STARTED_AT, 'http://localhost:3000')).toBeNull()
    expect(
      requestLogDashboardUrl(`${TRACE_ID}&x=1`, STARTED_AT, 'http://localhost:3000'),
    ).toBeNull()
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'not a url')).toBeNull()
  })
})
