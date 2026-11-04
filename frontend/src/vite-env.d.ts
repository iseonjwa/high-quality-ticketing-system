/// <reference types="vite/client" />

interface ImportMetaEnv {
  // 시연 모드가 여는 Grafana 기준 주소. 비어 있으면 `http://localhost:3000`
  readonly VITE_GRAFANA_URL?: string
}
