// 시연 모드 요청 로그 패널의 너비 범위. 화면 폭에 따라 상한이 바뀐다.
export const PANEL_MIN_WIDTH = 280
export const PANEL_MAX_WIDTH = 720
// 패널을 넓혀도 앱 화면이 가장 좁은 휴대폰 너비 밑으로 줄지 않게 한다.
// 앱 헤더는 헤더 너비 기준으로 여백을 줄이므로(AppLayout) 이 너비에서도 한 줄로 들어간다
const APP_MIN_WIDTH = 360
// 앱 최소 너비와 패널 최소 너비가 함께 들어가지 않는 화면(휴대폰)에서는 패널을 그리지 않는다
export const PANEL_VISIBLE_MIN_VIEWPORT = APP_MIN_WIDTH + PANEL_MIN_WIDTH

export function clampPanelWidth(width: number, viewportWidth: number): number {
  const max = Math.max(PANEL_MIN_WIDTH, Math.min(PANEL_MAX_WIDTH, viewportWidth - APP_MIN_WIDTH))
  return Math.min(Math.max(Math.round(width), PANEL_MIN_WIDTH), max)
}
