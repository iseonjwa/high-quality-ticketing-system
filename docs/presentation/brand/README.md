# 아이콘·색

아이콘, 로고처럼 여러 자료에 함께 쓰는 그림과 그림 규칙을 둔다. 색은 프론트 [디자인 시스템](../../design/frontend/design-system.md) 토큰을 따른다. 주 색은 `primary-600` `#3B3BD4`, 경고 아이콘 색은 `#F59F00`이다.

다이어그램은 [다이어그램 스타일](diagram-style.md)을 따른다. 캔버스, 색의 의미(동기 요청·이벤트·데이터), 글자 크기, 선 규칙, PNG 내보내기 방법(`script/render-diagram.sh`)이 있다.

| 날짜 | 파일 | 내용 |
|---|---|---|
| 2026-11-04 | [2026-11-04-slack-app-icon.png](2026-11-04-slack-app-icon.png), [원본 SVG](2026-11-04-slack-app-icon.svg) | 슬랙 알림 앱(`#이선좌-배포상태알림`·`#이선좌-성능-모니터링` 웹훅) 아이콘. 공연장 좌석에 선택 체크 배지, 프론트 토큰 `#3B3BD4`·`#F59F00`. 1024px PNG는 헤드리스 Chrome으로 SVG를 찍어 만들었다 |
| 2026-11-12 | [2026-11-12-charm-ticket-sample.png](2026-11-12-charm-ticket-sample.png), [원본 SVG](2026-11-12-charm-ticket-sample.svg) | 발권 이벤트 부적 시안(ISE-257). 1080×1920(9:16)으로 인스타그램 스토리 크기 그대로다. 예매 상세 티켓의 모양(`ADMIT n` 머리, 절취선과 양 끝 홈, 떼는 칸, 꾸밈 바코드)을 세로로 세우고, 위 칸에 펫(합격 머리띠 토끼), 닉네임, 전투력, 응원 문장을, 떼는 칸에 공연 날짜·좌석과 합격 기원 도장을 둔다. 바탕은 `#3B3BD4` 계열에 좌석 무늬다. 닉네임·전투력·공연은 예시 값이다. 헤드리스 Chrome으로 배율 1로 찍었다 |
| 2026-11-12 | [2026-11-12-charm-pets-sheet.png](2026-11-12-charm-pets-sheet.png) | 부적 펫 묶음. 펫은 미리 그린 부품(종류 10, 털색 4, 머리 장식 6(헤드폰·학사모·왕관·리본·새싹·별핀), 든 것 5, 조합 1,200개)을 겹친다. 위 두 줄은 종류 10가지, 아래 두 줄은 예매 ID 1001~1010에 실제로 배정되는 펫이다. 원본은 브랜치 `feat/ISE-257-charm-event`의 `frontend/src/pages/charm/charmPets.ts`이고 `frontend/tools/charm-art/preview.mjs`로 찍었다 |
| 2026-11-12 | [2026-11-12-charm-ticket-normal.png](2026-11-12-charm-ticket-normal.png), [2026-11-12-charm-ticket-shiny.png](2026-11-12-charm-ticket-shiny.png) | 부적 일반·반짝이(레어) 두 장, 1080×1920. 반짝이는 연속한 예매 16건마다 한 장이고 무지개 박 바탕, 박 테두리, 빛줄기, 금색 "레어 부적" 칩을 둔다. 응원 문장은 인생 전반의 동기부여·응원 40개이고, 떼는 칸 도장은 8가지 중 하나다. 닉네임은 앞 8자만 줄임표 없이 보인다. 원본은 같은 브랜치의 `charmTicket.ts`다 |
| 2026-11-12 | [2026-11-12-charm-stamps-sheet.png](2026-11-12-charm-stamps-sheet.png) | 부적 떼는 칸 도장 8가지. 소원 성취, 만사 형통, 행운 가득, 대박 기원, 건강 기원, 일취 월장은 붉은 인주색, 예매 말장난인 꽃길 예약, 행복 예매는 주 색 `#3B3BD4`다. 사각과 원형을 번갈아 두고 기울기를 조금씩 다르게 했다. 연속한 예매 8건마다 한 번씩 나온다 |

## 미리보기

<img src="2026-11-04-slack-app-icon.png" alt="슬랙 알림 앱 아이콘" width="160">

<img src="2026-11-12-charm-ticket-sample.png" alt="발권 이벤트 부적 시안" width="270">

<img src="2026-11-12-charm-pets-sheet.png" alt="부적 펫 묶음" width="420">

<img src="2026-11-12-charm-stamps-sheet.png" alt="부적 도장 8가지" width="560">

<img src="2026-11-12-charm-ticket-normal.png" alt="일반 부적" width="200"> <img src="2026-11-12-charm-ticket-shiny.png" alt="반짝이 부적" width="200">
