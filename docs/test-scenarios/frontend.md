# 프론트 화면 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 프론트 화면 (17건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| FE-001 | 로그인 화면: 형식 오류, 잠금, 임시 비밀번호, 돌아갈 곳 | 실패 | P1 | 형식 오류는 요청 안 함, 칸 안내, 로그인 뒤 이전 화면 복귀, 이전 사용자 캐시 제거 | `frontend/src/pages/auth/LoginPage.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-002 | API 클라이언트: 재발급 1회, 동시 401, 503 시 토큰 유지 | 동시성 | P0 | 재발급 1회, 4xx는 세션 만료, 503은 Refresh 유지 | `frontend/src/api/client.test.ts` | 자동 테스트 |
| FE-003 | API 클라이언트 실패 분류: 비공통 502, 네트워크, 스키마 불일치 | 실패 | P1 | 코드·traceId 담긴 오류, NETWORK_ERROR, RESPONSE_SCHEMA_MISMATCH, Retry-After 해석 | `frontend/src/api/client.test.ts` | 자동 테스트 |
| FE-004 | 라우트: 첫 화면, 없는 주소, 권한 가드 | 보안 | P1 | 첫 화면은 공연 목록, 없는 주소 안내. 관리자 가드 세부는 확인 필요 | `frontend/src/app/routes.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-005 | 공연 목록·상세·홈 화면 (S1) | 정상 | P1 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `frontend/src/pages/concerts/ConcertListPage.test.tsx`, `frontend/src/pages/concerts/ConcertDetailPage.test.tsx`, `frontend/src/pages/concerts/HomeHero.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-006 | 대기열 화면: 순번, 유효 토큰 즉시 이동, 불안정 안내, 만료·오픈 전·없는 회차 (S2) | 실패 | P0 | 상황별 안내와 복귀 경로, 예상 못한 오류는 문의 번호 | `frontend/src/pages/queue/QueuePage.test.tsx`, `frontend/src/pages/queue/useQueueAdmission.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-007 | 좌석 화면: 입장 토큰 없음, 구역 선택, 매수 한도, 멱등키 요청 (S3) | 정상 | P0 | 토큰 없으면 대기열로, 좌석 오름차순 전송, 선점 시 닫을 수 없는 대화상자 | `frontend/src/pages/seats/SeatsPage.test.tsx`, `frontend/src/pages/seats/useHoldRequest.test.ts` | 자동 테스트, 로컬, 서버 |
| FE-008 | 좌석 화면 실패: 이미 선택된 좌석, 결과 불명 재시도 (S5) | 실패 | P0 | 고를 수 없게 된 좌석만 빼고 유지, 같은 멱등키로 재시도 | `frontend/src/pages/seats/SeatsPage.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-009 | 결제 화면: 이동 값 없음, 확정·만료·토큰 없음, 서버 시계 보정 (S4) | 경계값 | P0 | 확정은 상세로, 만료는 안내, 시간 판단은 서버 시계 기준 | `frontend/src/pages/payment/PaymentPage.test.tsx` | 자동 테스트, 로컬 |
| FE-010 | 결제 진행 흐름: 확인 중 폴링과 결과 화면 (S6, S7) | 실패 | P0 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `frontend/src/pages/payment/usePaymentFlow.test.tsx`, `frontend/src/pages/payment/paymentFlow.test.ts` | 자동 테스트, 로컬, 서버 |
| FE-011 | 예매 상세: 티켓 카드와 번호, 번호 없는 좌석 처리 (S4) | 정상 | P1 | 좌석 수만큼 카드, 번호 없으면 바코드 없음 | `frontend/src/pages/reservations/ReservationPage.test.tsx`, `frontend/src/pages/reservations/MyReservationsPage.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-012 | 추첨 화면: 시계에 따른 단계, 매수 선택, 멱등키 (S8) | 정상 | P0 | 단계 전환, 1매 추첨은 선택 없음 | `frontend/src/pages/lotteries/LotteryPage.test.tsx` | 자동 테스트, 로컬, 서버 |
| FE-013 | 추첨 응모 실패: 504 재확인, 키 폐기, 409 처리 | 실패 | P1 | 504는 내 응모 재조회, 다른 실패는 새 키, 409는 이미 응모 또는 기간 안내 | `frontend/src/pages/lotteries/LotteryPage.test.tsx` | 자동 테스트 |
| FE-014 | 관리자 추첨 화면: 오픈 경과 경고, 칸 검증, 시간대 안내, 결과 표시 (S8) | 경계값 | P1 | 오픈 지나면 폼 없음, 값 규칙 위반 칸 안내, 새벽 3\~5시 밖 안내, 설정 후 추첨 번호 | `frontend/src/pages/admin/AdminLotteryPages.test.tsx`, `frontend/src/admin/lotteryFormSchema.test.ts` | 자동 테스트, 로컬, 서버 |
| FE-015 | 관리자 공연·회차·좌석·비밀번호 초기화 화면 | 정상 | P1 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `frontend/src/pages/admin/AdminPages.test.tsx`, `frontend/src/admin/formSchemas.test.ts` | 자동 테스트, 로컬, 서버 |
| FE-016 | 시연 모드: ?demo=on, 요청 기록 패널, traceId 링크 (S1) | 정상 | P0 | 최신 기록 위, 상태 칩, 16진수 아닌 traceId는 글자만, 상태는 sessionStorage | `frontend/src/demo/demoMode.test.ts`, `frontend/src/demo/RequestLogPanel.test.tsx`, `frontend/src/demo/requestLog.test.ts` | 자동 테스트, 로컬, 서버 |
| FE-017 | 브라우저 E2E: 가입부터 티켓 확인까지 (S1, S2, S3, S4) | 정상 | P1 | 확인할 질문: 끝까지 완료되는가. MSW 모드 Playwright는 로그인부터 내 예매까지(409·확인 중 포함)를 자동으로 돈다. 가입과 실제 스택(compose) 대상은 아직 없어 리허설로 수동 실행 | `frontend/e2e/booking-flow.spec.ts`(MSW 모드) | 로컬, 서버 |

### 프론트 화면: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| FE-001 | 화면 접속 | 상황별 로그인 | 있음 |
| FE-004 | 없음 | 각 주소 열기 | 없음 |
| FE-005 | 공연 존재 | 홈·목록·상세 이동 | 없음 |
| FE-006 | 로그인 | 상황별 대기열 화면 | 있음 |
| FE-007 | 입장 토큰 | 좌석 선택과 선점 | 있음 |
| FE-008 | 경합 또는 무응답 | 선점 실패 | 있음 |
| FE-009 | 예매 | 상태별 결제 화면 열기 | 없음 |
| FE-010 | 응답 유실 또는 거절 | 결제 후 화면 관찰 | 있음 |
| FE-011 | 확정 예매 | 상세·완료 화면 열기 | 없음 |
| FE-012 | 추첨 | 시작·마감·추첨 시각 경과와 응모 | 있음 |
| FE-014 | ADMIN | 추첨 설정 폼 입력 | 있음 |
| FE-015 | ADMIN | 각 폼 입력 | 있음 |
| FE-016 | 없음 | ?demo=on 접속 후 요청 | 없음 |
| FE-017 | 스택, 브라우저 | 실제 화면으로 가입·로그인·대기열·선점·결제·티켓 진행 | 있음 |
