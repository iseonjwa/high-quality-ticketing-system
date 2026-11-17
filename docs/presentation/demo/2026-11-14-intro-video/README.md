# 2분 서비스 소개 영상 (빅빵 편)

발표와 따로 트는 서비스 소개 광고 영상이다. 완성본은 [`../2026-11-14-intro-video.mp4`](../2026-11-14-intro-video.mp4)(92초, 1920×1080)이다. 기획은 ISE-280에 있다.

## 이야기

가상 사용자 하린(여자 주인공)이 아이돌 "빅빵" 콘서트 티켓 오픈을 맞는다. 작년엔 새로고침과 "이미 선택된 좌석입니다"에 막혔지만, 이선좌에서는 순번이 보이는 대기열로 들어가 좌석을 잡고, 결제가 거절돼도 좌석이 바로 돌아오고, 추첨에 응모해 두면 당첨과 자동결제까지 끝난다.

| 시간 | 장면 | 화면 | 소리 |
|---|---|---|---|
| 0~6.6초 | 훅 | 핑크 · 빅빵 월드투어, 오픈 카운트다운 | 하린: "이 시대 최고의 아이돌…" |
| 6.6~13.2초 | 긴장 | 노랑 · "이미 선택된 좌석입니다" 알림이 쌓임 | 하린: "작년엔 새로고침만…" |
| 13.2~20.2초 | 등장 | 코발트 · 앱 아이콘과 이선좌 | 내레이션 |
| 20.2~36.6초 | 대기열·좌석 선점 | 실제 화면 녹화(순번 240번 확대, "좌석을 잡았어요") | 내레이션, 하린: "잡았다!" |
| 36.6~54.4초 | 결제 거절 → 보상 → 재결제 | 실제 화면, 보상 도식(0.14초), 예매 확정 | 하린: "어? 카드가…", 내레이션 |
| 54.4~66.6초 | 추첨 | 실제 화면(카드 등록 → 응모 → 당첨되고 결제까지) | 내레이션, 하린 |
| 66.6~77.6초 | 숫자 | 노랑 · 1/100, 0.043초 | 내레이션 |
| 77.6~92초 | 티켓 · 엔드카드 | 핑크 티켓, 코발트 엔드카드 | 하린, 내레이션 |

## 사실 확인

- 화면은 모두 실제 프론트엔드(`frontend/`)를 목(MSW) 모드로 띄워 녹화했다. 녹화용 복사본에서만 공연 이름을 "빅빵 2026 월드투어"로 바꿨고 저장소 코드는 바꾸지 않았다.
- "같은 좌석에 100명, 성공 1명"은 동시성 테스트([`concurrency-results.md`](../../../design/reservation/concurrency-results.md) C1), "초당 2,000건에 0.043초"는 배포 서버 8차 측정([`open-peak-scenarios.md`](../../../design/infra/open-peak-scenarios.md))의 p95다.
- "0.14초"는 11/14 배포 서버 보상 캡처([`saga-payment-declined.md`](../../architecture/2026-11-14-saga-payment-declined.md)), "승인 34 = 34"는 [`f1-fault-injection.md`](../../../design/lottery/f1-fault-injection.md) F1-a다.

## 만든 방법

| 무엇 | 도구 | 파일 |
|---|---|---|
| 영상 조립 | HyperFrames 0.9.44(HTML을 프레임 단위로 렌더) | `index.html`, `compositions/`, 설계 `SPEC.md` |
| 화면 녹화 | Playwright + Chrome DevTools 스크린캐스트, 큰 커서 | `tools/record-screens.mjs`, 결과 `assets/video/` |
| 내레이션 | 로컬 VoiceStudio(OmniVoice). 하린과 내레이터 두 여성 목소리를 음성 설계로 만들고 같은 참조로 복제해 줄마다 목소리를 맞춤 | 대사 `tools/narration-lines.tsv`, 결과 `assets/voice/` |
| 효과음 | HyperFrames 기본 효과음(클릭·휙·알림·상승음) | 저장소에 넣지 않았다. 아래 명령으로 다시 받는다 |
| 글꼴 | Pretendard(OFL) | 저장소에 넣지 않았다. `frontend/node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2`를 `assets/fonts/`에 복사한다 |

다시 렌더하기(이 폴더에서):

```bash
mkdir -p assets/fonts assets/sfx
cp ../../../../frontend/node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2 assets/fonts/
for i in "soft mouse click" "fast whoosh transition" "pop notification" "riser into impact"; do npx hyperframes media-use resolve --type sfx --intent "$i" --project . --local-only; done
cp .media/audio/sfx/*.mp3 assets/sfx/
npx hyperframes check && npx hyperframes render -q delivery -o renders/intro.mp4
```

## 남은 것

- 배경음악이 없다. HeyGen 로그인(`npx hyperframes auth login`, 무료) 뒤 음악 목록에서 고르면 내레이션 아래(음량 0.14)에 깐다.
- 음량은 -13.3 LUFS(목표 -16~-14보다 조금 크다), 최대 -1.7 dBTP, 클리핑 0으로 쟀다. 귀로 들어 보고 확인하지는 못했다.
- 2분을 요청했지만 이야기에 맞춰 92초로 끝냈다. 늘려야 하면 AI 예매 상담 장면을 더한다.
