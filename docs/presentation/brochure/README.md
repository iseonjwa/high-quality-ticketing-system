# 최종 발표회 브로셔

Notion 「단기심화 10기 최종 발표회 브로셔」(teamsparta 공개 페이지)의 5조 팀 페이지에 올릴 내용이다. 제출은 11/18, 발표는 11/19다.

| 파일 | 내용 |
|---|---|
| [`2026-11-16-brochure-notion.md`](2026-11-16-brochure-notion.md) | **Notion에 그대로 붙여 넣는 본문**. 출처·내부 메모를 뺐다 |
| [`2026-11-16-brochure-draft.md`](2026-11-16-brochure-draft.md) | 정본 초안. 문체·개요·에셋·출처·검토 항목이 있다. 숫자를 고칠 때는 여기부터 고치고 붙여 넣기 본문에 옮긴다 |
| `2026-11-16-v1-vs-v2.*`, `2026-11-16-gateway-before-after.*`, `2026-11-16-autopay-before-after.*` | 트러블슈팅 전후 그림(PNG 업로드용, SVG 원본) |
| `2026-11-16-before-after-charts.py` | Gateway·자동결제 그림을 다시 만드는 스크립트 |
| [`stories/`](stories/) | 서비스별 이야기 하위 페이지 5개(`1-gateway.md` ~ `5-lottery.md`)와 썸네일(`thumb-*.png`, `make_thumbnails.py`, `thumbnails.json`) |
| `2026-11-16-8th-brochure-review.md`, `2026-11-16-10th-brochure-review.md` | 8기·10기 다른 조 브로셔 분석(본문 개편의 근거) |
| `2026-11-16-cover-banner.*`, `2026-11-16-cover-banner.py` | 갤러리 썸네일용 커버 배너(1500×688, Notion 카드 비율 약 2.18:1)와 생성 스크립트. 헤드라인 "빠른 손 아니어도, 추첨으로 내 자리"(차별점), 둘째 줄 "선착순은 구역만 고르면 자동배정"(기본기). 휴대폰 두 대는 디자인 시스템 색으로 그린 간략 목업이다: 선착순·자동배정(좌석 격자에 배정된 두 자리 + "자동배정 완료" 배지), 추첨 응모("당첨됐어요!" + "당첨 · 자동결제" 배지). 자동배정 화면은 프론트 적용 작업이 끝나면 실제 문구와 맞춘다. 요소 묶음은 가운데에 두고 가장자리에서 띄웠다 |

## Notion 등록 순서

1. 브로셔 갤러리에서 5조 칸("서비스 명을 입력해주세요." 두 개 중 5조 것)을 연다. 템플릿 본문(목차, 안내 문구, 빈 표)은 지우지 말고 아래 내용으로 덮는다.
2. **썸네일**: 페이지 맨 위 "커버 추가 → 업로드"로 `2026-11-16-cover-banner.png`를 올린다. 갤러리 카드에는 이 커버가 썸네일로 보인다. "아이콘 추가 → 업로드"로 `docs/presentation/brand/2026-11-04-slack-app-icon.png`를 올리면 카드 제목 옆에 붙는다. 커버가 위아래로 잘려 보이면 "위치 변경"으로 가운데에 맞춘다(핵심 요소는 가로 230~1270, 세로 가운데에 모아 두었다).
3. **속성 칸**을 채운다(아래 표).
4. **본문**: `2026-11-16-brochure-notion.md`를 메모장 등으로 열어 2번째 줄부터 끝까지 복사해 템플릿 목차 아래에 붙여 넣는다. 첫 줄(`# 이선좌 : …`)은 템플릿의 "서비스/프로젝트명 : 한 줄 소개" 제목 칸에 직접 쓴다.
   - Notion은 마크다운 붙여 넣기를 제목·목록·표·굵게로 바꿔 준다. 표가 텍스트로 들어오면 그 부분만 다시 붙여 넣거나 `/표`로 만든다.
   - CONTRIBUTORS 표의 "▶" 앞에서 Shift+Enter로 줄을 나눈다(붙여 넣기로는 칸 안 줄바꿈이 안 된다).
5. **서비스별 이야기 갤러리**: 본문 트러블슈팅의 `〔하위 페이지 갤러리 …〕` 자리에 `/갤러리 보기`로 인라인 데이터베이스를 만들고, 하위 페이지 5개를 새 항목으로 추가한다. 항목마다 `stories/N-*.md` 본문을 붙여 넣고(첫 줄은 항목 제목), 커버로 `stories/thumb-N-*.png`를 올린다. 갤러리 레이아웃의 카드 미리보기를 "페이지 커버"로 둔다.
6. **그림**: 본문의 `〔그림: 파일명〕` 줄마다 아래 표의 파일을 끌어다 놓고 그 줄을 지운다.
7. **〔팀 확인: …〕** 표시는 팀 답을 받아 채우고 지운다. 남아 있으면 등록하지 않는다.
8. 템플릿 안내 문구(회색 글씨 "~을 넣어주세요", "좋은 예/나쁜 예" 예시 표)를 지운다.
9. 마지막에 Notion 페이지를 처음부터 읽으며 그림이 빠진 곳, 표가 깨진 곳이 없는지 본다.

## 속성 칸

| 칸 | 값 | 상태 |
|---|---|---|
| 서비스명(페이지 제목) | 이선좌 | |
| 서비스/프로젝트 한 줄 소개 | 오픈 정각에 몰리는 콘서트 예매를 중복 판매 없이 처리하는 티켓팅 서버 | 팀 확인 |
| Team Notion URL | — | 팀이 정함(공개할 페이지가 있으면) |
| Github URL | https://github.com/iseonjwa/high-quality-ticketing-system | |
| Front URL | https://52.63.10.174/ | 11/16 인증서 검증 포함 200 확인 |
| Backend URL | https://52.63.10.174/api (Gateway 단일 진입점) | |
| 마감일 | 2026-11-18 | 발제 일정 기준 |
| 팀 명 | 이선좌 | |
| 발표자료 링크 | — | 발표 자료가 나오면 |
| 팀 번호 | 5 | |

## 올릴 그림

| 본문 위치 | 파일(저장소 경로) |
|---|---|
| 커버(썸네일) | `docs/presentation/brochure/2026-11-16-cover-banner.png`(1500×688, 원본 `.svg`) |
| 페이지 아이콘 | `docs/presentation/brand/2026-11-04-slack-app-icon.png` |
| 메인 이미지 | `docs/presentation/brochure/2026-11-16-cover-banner.png`(커버와 같은 그림, 11/16 A안). 배너의 "중복 판매 0건"은 배포 서버 측정(사용자 3,000명, 모든 실행) 결과다 |
| 인프라 설계도 | `docs/presentation/architecture/2026-11-11-system-architecture.png` |
| 주요 기능 | `docs/presentation/architecture/2026-11-12-user-flow-booking.png`, `2026-11-12-user-flow-lottery-account.png`, `2026-11-14-saga-payment-declined.png` |
| 서비스별 이야기 카드 커버 | `docs/presentation/brochure/stories/thumb-1-gateway.png` ~ `thumb-5-lottery.png` |
| 트러블슈팅 A | `docs/presentation/brochure/2026-11-16-gateway-before-after.png` |
| 트러블슈팅 B | `docs/presentation/brochure/2026-11-16-v1-vs-v2.png` |
| 트러블슈팅 C | `docs/presentation/brochure/2026-11-16-autopay-before-after.png` |

## 등록 전에 남은 확인

정본 초안 6절 "검토"가 목록이다. 붙여 넣기 본문에 영향을 주는 것만 다시 적는다.

- 한 줄 소개 문장(팀)
- 메인 이미지(팀)
- 기여 문장(각자)
- Java 21 선택 이유(결제 담당)
- AI 판단 기록 건수(등록 날 다시 셈)
- 전후 그림을 Grafana 캡처로 바꿀지(팀)
