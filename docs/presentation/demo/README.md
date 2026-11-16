# 시연

시연 화면 스크린샷, 녹화, 발표 중 시연 순서표를 둔다. 라이브 시연이 실패할 때를 대비한 녹화도 여기에 둔다.

정본:
- 시연 시나리오(사전 준비, 장면, DB 확인, 장애 주입): [`design/demo/2026-11-06-mvp-scenarios.md`](../../design/demo/2026-11-06-mvp-scenarios.md)
- 결제 장애 시나리오를 보여 주는 순서: [`guides/demo-scenarios.md`](../../guides/demo-scenarios.md)
- 리허설 기록: [`test-scenarios/runs/2026-11-06-rehearsal.md`](../../test-scenarios/runs/2026-11-06-rehearsal.md)

| 날짜 | 파일 | 내용 |
|---|---|---|
| 2026-11-16 | [2026-11-16-intro-video-v2.mp4](2026-11-16-intro-video-v2.mp4), [원본 폴더](2026-11-16-intro-video-v2/README.md) | 소개 영상 v2(75초). 웹툰풍 하린이 모든 장면에 나오는 모션그래픽 영상(codex 그림, 스쿼시 앤 스트레치). 새 문구 데스크톱 화면 녹화를 브라우저 창에 넣고(폰은 강의실·공연장 장면만), 결제사 연결 불안정 뒤 다시 결제, 추첨, 부적 이벤트까지. 로컬 VoiceStudio 목소리, 배경음악 CC BY |
| 2026-11-16 | [2026-11-16-screens/](2026-11-16-screens/) | 브로셔용 화면 캡처 32장. 프론트 MSW 목 모드를 Playwright로 찍었다(데스크톱 1440×900 2배, 모바일 390×844 3배). 01 공연 목록 ~ 27 모바일 대기열. 자동 배정 화면은 없고(API만), 25번 traceId는 목 값이다 |
| 2026-11-14 | [2026-11-14-intro-video.mp4](2026-11-14-intro-video.mp4), [원본 폴더](2026-11-14-intro-video/README.md) | 2분 서비스 소개 광고 영상(92초). 가상 사용자 하린의 빅빵 콘서트 예매: 대기열·좌석 선점, 결제 거절 뒤 좌석 원복, 추첨 자동결제. 실제 화면 녹화, 여성 AI 내레이션(로컬 VoiceStudio), HyperFrames로 조립 |
