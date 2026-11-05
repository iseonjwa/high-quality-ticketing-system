import { afterEach, describe, expect, it, vi } from 'vitest'
import { defaultGrafanaUrl, requestLogDashboardUrl } from '@/demo/grafana'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'
const STARTED_AT = 1_800_000_000_000
const FROM = STARTED_AT - 900_000
const TO = STARTED_AT + 900_000

describe('requestLogDashboardUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

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

  // 개인 `.env.local`의 값이 결과를 바꾸지 않게 환경 변수를 테스트 안에서 정한다
  it('VITE_GRAFANA_URL이 비어 있으면 화면을 연 호스트의 3000번을 쓴다', () => {
    vi.stubEnv('VITE_GRAFANA_URL', '')

    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT)).toMatch(/^http:\/\/localhost:3000\/d\//)
  })

  it('기본 주소는 화면을 연 호스트와 scheme을 따른다', () => {
    expect(defaultGrafanaUrl({ protocol: 'http:', hostname: '52.63.10.174' })).toBe(
      'http://52.63.10.174:3000',
    )
    expect(
      requestLogDashboardUrl(
        TRACE_ID,
        STARTED_AT,
        defaultGrafanaUrl({ protocol: 'http:', hostname: '52.63.10.174' }),
      ),
    ).toBe(
      `http://52.63.10.174:3000/d/iseonjwa-request-logs?orgId=1&var-traceId=${TRACE_ID}&from=${FROM}&to=${TO}`,
    )
  })

  it('VITE_GRAFANA_URL이 있으면 그 주소를 쓴다', () => {
    vi.stubEnv('VITE_GRAFANA_URL', 'http://grafana.internal:3000')

    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT)).toMatch(
      /^http:\/\/grafana\.internal:3000\/d\/iseonjwa-request-logs\?/,
    )
  })

  it('잘못된 traceId나 기준 주소는 null이다', () => {
    expect(requestLogDashboardUrl('abc', STARTED_AT, 'http://localhost:3000')).toBeNull()
    expect(requestLogDashboardUrl('X'.repeat(32), STARTED_AT, 'http://localhost:3000')).toBeNull()
    expect(
      requestLogDashboardUrl(`${TRACE_ID}&x=1`, STARTED_AT, 'http://localhost:3000'),
    ).toBeNull()
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'not a url')).toBeNull()
    // scheme 없이 적은 주소와 http(s)가 아닌 주소
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'localhost:3000')).toBeNull()
    expect(requestLogDashboardUrl(TRACE_ID, STARTED_AT, 'javascript:alert(1)')).toBeNull()
  })
})
