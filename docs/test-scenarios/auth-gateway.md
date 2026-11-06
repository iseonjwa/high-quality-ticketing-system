# 인증·Gateway 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 인증·권한 (27건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| AUTH-001 | 가입·로그인·내 정보 조회 | 정상 | P0 | 가입 성공, 토큰 발급, 내 정보 200 | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬, 서버 |
| AUTH-002 | 중복 이메일 가입 거절 | 실패 | P1 | 409 EMAIL_DUPLICATED | `user-service/AuthIntegrationTest` | 자동 테스트, 로컬, 서버 |
| AUTH-003 | 대소문자만 다른 이메일을 같은 계정으로 처리 | 경계값 | P2 | 같은 계정으로 판단, 공백 제거 후 소문자 저장 | `user-service/AuthIntegrationTest`, `user-service/UserTest` | 자동 테스트 |
| AUTH-004 | 잘못된 입력 가입 거절 | 실패 | P2 | 400 검증 실패, 필드와 사유 반환 | `user-service/AuthIntegrationTest` | 자동 테스트, 로컬 |
| AUTH-005 | 잘못된 비밀번호 로그인 거절 | 실패 | P1 | 401 INVALID_CREDENTIALS | `user-service/AuthIntegrationTest` | 자동 테스트, 로컬, 서버 |
| AUTH-006 | 로그인 5회 실패 시 계정 잠금 | 경계값 | P1 | 잠금 뒤 맞는 비밀번호도 거절(ACCOUNT_LOCKED 423) | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| AUTH-007 | 로그인 성공 시 실패 횟수 초기화 | 경계값 | P2 | 횟수가 0부터 다시 쌓인다 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-008 | 비밀번호 72바이트 경계(ASCII) | 경계값 | P2 | 72바이트 성공, 73바이트 거절 | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트 |
| AUTH-009 | 다중 바이트 비밀번호 UTF-8 72바이트 경계 | 경계값 | P2 | 글자 수가 아니라 바이트로 경계 적용 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-010 | Refresh 회전과 이전 토큰 재사용 거절 | 보안 | P0 | 새 토큰 발급, 이전 토큰 거절 | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| AUTH-011 | 같은 Refresh 동시 재사용 | 동시성 | P1 | 하나만 성공 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-012 | 같은 이메일 동시 가입 | 동시성 | P1 | 하나만 성공 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-013 | 로그아웃 시 Refresh 폐기와 Access 블랙리스트 | 보안 | P0 | 둘 다 거절 | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬, 서버 |
| AUTH-014 | 새 로그인이 이전 Refresh를 대체, 토큰 원문 미저장 | 보안 | P1 | 이전 Refresh 거절, 저장소에 원문 없음 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-015 | Access 토큰을 Refresh로 사용 거절 | 보안 | P1 | 둘 다 거절 | `user-service/AuthIntegrationTest`, `common/TokenDecodersTest` | 자동 테스트 |
| AUTH-016 | Access 토큰 위조·변조 거절(키·발급자·대상·역할·짧은 키) | 보안 | P0 | 모두 거절, 만료와 다른 실패를 구분 | `common/TokenDecodersTest` | 자동 테스트 |
| AUTH-017 | 관리자의 비밀번호 초기화와 임시 비밀번호 변경 | 정상 | P1 | 잠금 해제, 변경 전에는 PASSWORD_CHANGE_REQUIRED(403), 변경 뒤 정상 | `user-service/AuthIntegrationTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| AUTH-018 | 일반 사용자의 비밀번호 초기화 거절, 없는 사용자 404 | 보안 | P1 | USER 거절, 없는 사용자 404 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-019 | 현재 비밀번호가 틀린 변경 시 실패 횟수 증가 | 실패 | P2 | 거절, 실패 횟수 증가 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-020 | 새 비밀번호가 현재와 같거나 짧으면 거절 | 경계값 | P2 | 거절 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-021 | 비밀번호 초기화·변경 중 이전 비밀번호 로그인 경합 | 동시성 | P1 | 이전 비밀번호 로그인은 세션을 만들지 못한다 | `user-service/AuthIntegrationTest` | 자동 테스트 |
| AUTH-022 | 잠긴 계정·변경 필요 계정의 Refresh 재발급 거절 | 보안 | P1 | 재발급 거절, 기존 세션 회전 안 함. 잠금과 임시 비밀번호가 함께면 잠금을 먼저 알림 | `user-service/AuthIntegrationTest`, `user-service/UserTest` | 자동 테스트 |
| AUTH-023 | 인증 설정값이 잘못되면 기동 거부 | 실패 | P2 | 기동 실패, 정상 값이면 기동 | `user-service/AuthConfigurationTest` | 자동 테스트 |
| AUTH-024 | RequireRole 권한 분기(없음 401, 다른 역할 403) | 보안 | P1 | 401, 403, 통과. 어노테이션이 없는 API는 역할 미확인 | `common/CommonWebTest` | 자동 테스트 |
| AUTH-025 | 형식이 잘못된 Gateway 사용자 헤더 | 보안 | P1 | 401. 헤더가 없으면 속성 없이 통과하고 사용자 필요 API에서 401 | `common/GatewayIdentityFilterTest`, `common/CommonWebTest` | 자동 테스트 |
| AUTH-026 | Redis 장애 중 인증은 503, 복구 뒤 새 인증 성공 | 실패 | P1 | 장애 중 503으로 구분, 복구 뒤 성공 | `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| AUTH-027 | 재기동 뒤 첫 인증 요청이 User까지 도달 | 경계값 | P2 | 첫 요청이 User 서비스까지 도달 | `system-tests/WorkspaceSystemTest`, `gateway/GatewayRedisWarmupTest` | 자동 테스트, 로컬 |

### 인증·권한: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| AUTH-001 | 미가입 이메일 | POST /api/v1/users → POST /api/v1/auth/login → GET /api/v1/users/me | 있음 |
| AUTH-002 | 이미 가입한 이메일 | 같은 이메일로 POST /api/v1/users | 없음 |
| AUTH-004 | 없음 | 형식이 틀린 이메일·비밀번호로 가입 | 없음 |
| AUTH-005 | 가입 계정 | 틀린 비밀번호로 로그인 | 있음 |
| AUTH-006 | 가입 계정 | 틀린 비밀번호 5회 → 맞는 비밀번호로 로그인 | 있음 |
| AUTH-010 | 로그인 상태 | POST /auth/refresh → 이전 Refresh로 다시 요청 | 있음 |
| AUTH-013 | 로그인 상태 | POST /auth/logout → 같은 Access·Refresh 사용 | 있음 |
| AUTH-017 | 잠긴 계정, ADMIN 토큰 | POST /admin/users/{id}/password-reset → 임시 비밀번호 로그인 → 변경 | 있음 |
| AUTH-026 | compose 스택 | Redis 중지 → 인증 요청 → Redis 재기동 → 재요청 | 서버 실행 안 함 |
| AUTH-027 | compose 스택 재기동 | 재기동 직후 인증 요청 | 서버 실행 안 함 |

## Gateway (10건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| GW-001 | USER는 관리자 경로 차단, ADMIN은 검증 헤더로 전달 | 보안 | P0 | USER 차단, ADMIN 통과 | `gateway/GatewayAuthenticationFilterTest`, `gateway/AccessPolicyTest` | 자동 테스트, 로컬, 서버 |
| GW-002 | 위조 사용자 헤더 제거와 검증 신원 전달 | 보안 | P0 | 제거 또는 토큰 값으로 대체 | `gateway/GatewayAuthenticationFilterTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬, 서버 |
| GW-003 | 내부 경로는 유효 토큰이어도 404 | 보안 | P0 | 404 | `gateway/GatewayAuthenticationFilterTest` | 자동 테스트, 로컬, 서버 |
| GW-004 | 보호 경로 토큰 없음·깨짐 401, 만료 TOKEN_EXPIRED | 실패 | P0 | 401 UNAUTHORIZED, 만료는 TOKEN_EXPIRED, 서명 틀린 만료 토큰은 만료를 알리지 않음 | `gateway/GatewayAuthenticationFilterTest` | 자동 테스트, 로컬, 서버 |
| GW-005 | 입장 토큰 필터: 좌석 조회·선점 전달과 403 (S2) | 보안 | P0 | 정상 토큰만 전달, 나머지 403 ENTRY_TOKEN_INVALID | `gateway/EntryTokenFilterTest` | 자동 테스트, 로컬, 서버 |
| GW-006 | 경로 변형으로 필터·관리자 정책 우회 시도 | 보안 | P0 | 우회 불가 | `gateway/EntryTokenFilterTest`, `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| GW-007 | 관리 엔드포인트 Gateway 비공개 | 보안 | P0 | 비공개 | `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬, 서버 |
| GW-008 | Redis 예열: PING 전 기동 보류, 재시도 제한 | 실패 | P2 | 일시 실패는 재확인, 지속 실패는 기동 실패, 무한 대기 없음 | `gateway/GatewayRedisWarmupTest` | 자동 테스트 |
| GW-009 | 공통 응답: 404·405·잘못된 JSON·예상 못한 예외 | 실패 | P1 | 공통 실패 형식, 405 Allow 유지, 내부 상세 미노출 | `common/CommonWebTest`, `common/ResponseFormatTest` | 자동 테스트, 로컬, 서버 |
| GW-010 | [미구현] 사용자 단위 요청 제한 429 | 보안 | P1 | SA Redis 키 ratelimit:{userId}는 설계에 있으나 Gateway·설정에 구현 없음. RATE_LIMITED 코드만 존재 (근거: 설계 문서) | 없음 | 로컬 |

### Gateway: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| GW-001 | 역할별 토큰 | /api/v1/admin/** 호출 | 없음 |
| GW-002 | 없음 | 사용자 헤더를 위조해 공개·보호 경로 호출 | 없음 |
| GW-003 | 토큰 | /internal/** 호출 | 없음 |
| GW-004 | 없음 | 각 토큰으로 호출 | 없음 |
| GW-005 | 로그인 | 없음, 위조, 만료, 다른 회차, 다른 사용자, 결제 토큰, 깨진 토큰으로 호출 | 없음 |
| GW-006 | 없음 | 경로 변형으로 호출 | 없음 |
| GW-007 | compose 스택 | Gateway로 actuator 호출 | 없음 |
| GW-009 | 없음 | 각 상황 요청 | 없음 |
| GW-010 | 로그인 | 초당 한도 초과 요청 | 서버 실행 안 함 |
