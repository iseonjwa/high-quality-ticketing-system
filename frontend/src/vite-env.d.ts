/// <reference types="vite/client" />

interface ImportMetaEnv {
  // 시연 모드가 여는 Grafana 기준 주소. 비어 있으면 http 화면은 `<호스트>:3000`, https 화면은 같은 주소의 `/grafana`(demo/grafana.ts)
  readonly VITE_GRAFANA_URL?: string
}
