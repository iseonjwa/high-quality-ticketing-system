export const REQUEST_LOG_DASHBOARD_UID = 'iseonjwa-request-logs'
export const REQUEST_LOG_TRACE_VARIABLE = 'traceId'

// 발표자 노트북이 SSH 터널로 Grafana를 localhost:3000에 연다.
const DEFAULT_GRAFANA_URL = 'http://localhost:3000'

// 요청 시각 앞뒤로 15분을 본다. 브라우저와 서버 시계가 조금 어긋나도,
// 요청 뒤 Kafka 소비자가 처리한 로그까지 같은 화면에 들어오게 하려는 것이다.
const TIME_RANGE_MS = 15 * 60 * 1000

const TRACE_ID_PATTERN = /^[0-9a-f]{16,32}$/

function configuredGrafanaUrl(): string {
  const configured = import.meta.env.VITE_GRAFANA_URL
  return configured !== undefined && configured !== '' ? configured : DEFAULT_GRAFANA_URL
}

// traceId로 Grafana "요청 로그" 대시보드를 여는 주소. 만들 수 없으면 null이다.
export function requestLogDashboardUrl(
  traceId: string,
  startedAt: number,
  grafanaUrl: string = configuredGrafanaUrl(),
): string | null {
  if (!TRACE_ID_PATTERN.test(traceId)) {
    return null
  }

  let url: URL
  try {
    url = new URL(grafanaUrl)
  } catch {
    return null
  }

  // 기준 주소에 하위 경로(`/grafana/`)가 있어도 그 아래로 붙인다.
  url.pathname = `${url.pathname.replace(/\/+$/, '')}/d/${REQUEST_LOG_DASHBOARD_UID}`
  url.search = new URLSearchParams({
    orgId: '1',
    [`var-${REQUEST_LOG_TRACE_VARIABLE}`]: traceId,
    from: String(startedAt - TIME_RANGE_MS),
    to: String(startedAt + TIME_RANGE_MS),
  }).toString()
  url.hash = ''
  return url.toString()
}
