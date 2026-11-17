# 서비스 소개 영상 v2 (빅빵 편, 하린 등장)

발표와 따로 트는 서비스 소개 광고 영상의 두 번째 판이다. 완성본은 [`../2026-11-16-intro-video-v2.mp4`](../2026-11-16-intro-video-v2.mp4)(75초, 1920×1080)이다. v1([`../2026-11-14-intro-video/`](../2026-11-14-intro-video/))에서 실제 화면 녹화는 그대로 살리고, 주인공 하린을 모든 장면에 세우고, 목소리를 다시 만들었다. 콘티는 Claude Docs 문서 "빅빵 편 소개 영상 콘티 v2"(비공개)에서 준현이 직접 고쳤다. 판단 기록은 [`2026-11-16-intro-video-v2-character-voice.md`](../../../ai-judgments/junhyun/2026-11-16-intro-video-v2-character-voice.md)에 있다.

## 이야기

| 시간 | 장면 | 화면 | 소리 |
|---|---|---|---|
| 0~3.6초 | 오픈 직전 | 밤, 하린의 방. 노트북 앞에서 카운트다운 19:59:52 | 하린 "이번엔 절대 못 놓쳐!" |
| 3.6~6.5초 | 작년의 기억 | 색을 뺀 회상. 노트북 앞 하린 둘레의 브라우저 창마다 "이미 선택된 좌석입니다" 알림이 쌓인다 | 하린 "작년엔 누르는 자리마다 다 나갔었지…" |
| 6.5~13.1초 | 이선좌 등장 | 20:00, 노트북 화면의 예매하기를 커서로 누르고 이선좌로 | 내레이션 n01 |
| 13.1~22.5초 | 대기열 | 브라우저 창에 실제 데스크톱 녹화, 순번 확대. 긴장 → 안도 | 내레이션 n02 |
| 22.5~28.1초 | 좌석 선택, 잡았다 | 녹화 "좌석 선택이 완료되었습니다" → 점프 | 내레이션 n03, 하린 "잡았다!" |
| 28.1~38.5초 | 결제사 불안정 → 다시 결제 | 녹화 "결제사 연결이 원활하지 않습니다" → 다시 결제 → 예매 확정. 당황 → 안도 → 기쁨 | 하린 "어? 결제가 안 되네?", 내레이션 n04 |
| 38.5~45.9초 | 추첨 | 전날 밤 브라우저 창의 응모 녹화 → 다음 날 아침 강의실, 폰에 뜬 당첨 및 결제 완료 화면 | 내레이션 n05, 하린 "자는 사이에 당첨에, 결제까지?" |
| 45.9~56.5초 | 숫자 | 같은 좌석 100명 → 1명, 초당 2,000건 → 0.043초. 엄지척 | 내레이션 n06 |
| 56.5~63.1초 | 공연 날 | 왕관 응원봉을 든 뒷모습 → 폰 속 확정 티켓 → 끝 문장 | 하린 "빅빵, 기다려! 내가 간다!", 내레이션 "공정하고 정확한 예매. 이선좌." |
| 63.1~71.1초 | 부적 | 브라우저 창의 예매 완료 QR → 부적 5장(레어 1장)과 원뿔 폭죽 | 내레이션 n08 |
| 71.1~75.1초 | 엔드카드 | 로고, "공정하고 정확한 예매", 음악 출처 | |

대사 전문은 [`tools/narration-lines.tsv`](tools/narration-lines.tsv)에 있다.

## 사실 확인

- 장면 6은 결제사 연결 불안정이다. 카드가 거절되면 서비스는 선택한 좌석을 바로 해제하므로, "좌석이 잡혀 있는 동안 다시 결제할 수 있다"는 내레이션은 결제사 연결 불안정(503 `PG_UNAVAILABLE`, 좌석 유지) 화면과 맞춘다.
- 이선좌는 웹 서비스라 화면 녹화는 실제 프론트엔드를 목(MSW) 모드, 데스크톱 크기(1280×760, 배율 1.5)로 띄워 찍고 브라우저 창 틀에 넣었다. 폰은 실제로 폰을 쓰는 두 장면(강의실에서 당첨 알림 확인, 공연장 입장 티켓)에만 쓴다. 강의실 장면의 당첨 화면과 공연 날 확정 티켓은 모바일 크기로 찍은 정지 화면이다. 녹화용 복사본에서만 공연 이름을 "빅빵 2026 월드투어 〈BIG BBANG〉"으로, 소개 글을 빅빵 공연에 맞게 바꿨다. 저장소 코드는 바꾸지 않았다.
- 부적 5장은 앱의 부적 코드(`frontend/src/pages/charm/`)로 하린 닉네임과 빅빵 공연을 넣어 그렸다([`tools/charm-video-cards.mjs`](tools/charm-video-cards.mjs)).
- "같은 좌석 100명 → 1명"은 좌석 선점 동시성 테스트([`concurrency-results.md`](../../../design/reservation/concurrency-results.md) C1, 3라운드 모두 1명 성공), "초당 2,000건 → 0.043초"는 배포 서버 8차 측정([`open-peak-scenarios.md`](../../../design/infra/open-peak-scenarios.md))의 대기열 진입 p95다.

## 만든 방법

| 무엇 | 도구 | 파일 |
|---|---|---|
| 하린과 배경 그림 | codex CLI 0.167 기본 이미지 생성. 인물 시트 3장을 먼저 만들고, 그 시트를 참조로 장면 그림을 만들었다. 프롬프트에 "평면 색, 그레인·노이즈·종이 질감·망점 금지"를 넣어 자글거림을 없앴다 | 요청문 `tools/codex-request-*.md`(5번은 노트북으로 다시 그린 컷), 실제 프롬프트 `tools/codex-image-prompts.md`, 결과 `assets/img/` |
| 하린 오려 내기 | 평면 바탕을 가장자리에서 채워 지우는 스크립트(Pillow, SciPy) | `tools/cutout.py`, 결과 `assets/img/harin-*.webp` |
| 화면 녹화 | Playwright + Chrome DevTools 스크린캐스트, 데스크톱 크기, 커서와 누르는 자리의 원 | `tools/record-screens.mjs`, 결과 `assets/video/` |
| 목소리 | 로컬 VoiceStudio(OmniVoice). 하린은 음성 설계 "female, young adult, high pitch"(시드 11), 내레이터는 "female, middle-aged, low pitch"(시드 5, 속도 1.0)로 만든 소리를 참조로 줄마다 복제했다. 방송용 압축 없이(`raw`), 품질 단계 48, `class_temperature` 0.6 | `tools/narration-lines.tsv`, 결과 `assets/voice/` |
| 배경음악 | "Life of Riley" Kevin MacLeod (incompetech.com), Licensed under Creative Commons: By Attribution 4.0 License(http://creativecommons.org/licenses/by/4.0/). 엔드카드에 출처를 적었다 | `assets/music/life-of-riley.mp3`(앞 76초만 잘라 둠) |
| 효과음 | HyperFrames 기본 효과음(클릭, 휙, 팝, 째깍, 폭죽) | 저장소에 넣지 않았다. 아래 명령으로 다시 받는다 |
| 영상 조립 | HyperFrames(HTML을 프레임 단위로 렌더). 설계 `SPEC.md` | `index.html`, `compositions/` |

## 다시 렌더하기(이 폴더에서)

```bash
mkdir -p assets/fonts assets/sfx
cp ../../../../frontend/node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2 assets/fonts/
for i in "soft mouse click" "fast whoosh transition" "pop notification" "soft clock tick" "party popper confetti burst" "whoosh swipe" "pop bubble"; do npx hyperframes media-use resolve --type sfx --intent "$i" --project . --local-only; done
cp .media/audio/sfx/*.mp3 assets/sfx/
npx hyperframes check && npx hyperframes render -q delivery -o renders/intro-v2.mp4
```

효과음 파일 이름(`sfx_001`~`sfx_007`)은 위 순서로 받았을 때의 이름이다. 순서가 바뀌면 `index.html`의 효과음 경로를 맞춘다.

## 남은 것

- 소리는 음량을 숫자로만 맞췄다(대사 -16 LUFS, 음악 0.12). 귀로 들어 확인하는 것은 준현이 한다.
- 내레이션 n01·n02의 "이선좌는"이 받아쓰기에서 "이선자는"으로 들렸다. 표기와 시드를 바꿔도 같았다. 어색하면 문장을 바꾼다.
