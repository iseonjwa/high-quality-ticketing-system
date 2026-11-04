# 0008. 내부 신뢰 모델과 인증 설계

- 상태: 설계 중 (SA 7-2·7-3 유지로 결정. 보강안은 선택 대기)
- 날짜: 2026-10-27
- 결정자: Junhyun An
- 관련 AI 판단: [신뢰 모델: AI 추천(서비스 JWT 직접 검증 + RS256) 대신 SA 유지](../ai-judgments/junhyun/2026-10-27-auth-trust-follow-sa.md)
- 선행: [ADR 0005](0005-auth-redis-fail-closed.md), [ADR 0006](0006-gateway-single-access-policy.md), [ADR 0007](0007-login-lockout-admin-reset.md)

## 맥락

ADR 0006으로 경로별 인증 판정을 Gateway 한 곳에 모으면서 서비스 단독 방어가 약해졌다. 서비스는 Gateway가 넣어 준 `X-User-Id`·`X-User-Role`을 그대로 믿고, 이 헤더에는 서명이 없다. Gateway를 거치지 않고 서비스에 닿는 요청은 임의 사용자·ADMIN으로 행세할 수 있다.

이 구조는 SA MASTER v3.1의 현재 설계와 같다. 7-2는 "Gateway가 JWT 검증 → 헤더 전달, 인증이 서비스 호출 없이 끝남"이고 9-2는 서비스 포트를 외부에 열지 않는다. 7-3은 HMAC이 검증자도 서명할 수 있다는 한계를 알고도 비대칭 서명을 MVP 이후로 미뤘다.

## 결정 (2026-10-27, Junhyun An)

SA 7-2·7-3을 유지한다. Gateway가 Access JWT(HS256)를 1차 검증하고 `X-User-*`를 전달하며, 서비스는 그 헤더를 신뢰한다. 서비스 직접 JWT 검증과 RS256 비대칭 서명은 MVP 이후로 미룬다. 서비스 포트를 외부에 공개하지 않는 배포 구성이 이 모델의 전제다.

## 고민 기록 (이번에 채택하지 않은 안)

- 사용자가 D1에서 C를 먼저 선택했고 이유로 "헤더 위조를 막고, 게이트웨이를 전적으로 신뢰하지 않음으로서 보안을 보강하기 위해서"를 밝혔다([ADR 0009](0009-service-access-jwt-verification.md), [C 선택 기록](../ai-judgments/junhyun/2026-10-27-service-jwt-verification-choice.md), 학습 노트 [서비스 신원 검증](../learning/junhyun/qna/2026-10-27-msa-service-identity-verification.md)·[HS256 vs RS256](../learning/junhyun/qna/2026-10-27-jwt-hs256-vs-rs256.md)).
- 같은 날 D2 RS256, D3 Access만 먼저도 AI 추천대로 골랐다가 "비대칭 서명은 MVP 이후로 미루는 게 맞다. 방금 논의한 부분은 SA대로"라며 모두 철회했다. 철회한 설계는 구현하지 않았다.
- 아래는 그때 검토한 내용이며 채택하지 않았다.

### 위협 모델

| # | 위협 | 현재 상태 | 영향 |
|---|---|---|---|
| T1 | 내부망·SSRF·설정 실수로 서비스에 직접 도달한 요청이 헤더를 위조 | 서명 없음. 보호는 네트워크 격리뿐(Compose 비공개 포트, 보안그룹) | 임의 사용자·ADMIN 행세, 관리자 API 호출 |
| T2 | JVM 모드·로컬 포트처럼 격리가 약한 환경 | 서비스 포트를 loopback에 여는 개발용 구성이 있다 | 같은 기기의 다른 프로세스가 위조 |
| T3 | 대칭 서명(HS256): 검증 키로 서명도 가능 | Gateway가 Access 키를 가진다 | Gateway 침해 시 모든 사용자 토큰 위조 |
| T4 | Redis 무인증: 블랙리스트·Refresh 해시·대기열이 같은 Redis | 인증 없음, 내부망 의존 | 블랙리스트 삭제로 로그아웃 우회, 대기열 조작 |
| T5 | Kafka·서비스 간 호출에 신원이 없음 | 이벤트 payload의 userId를 그대로 믿는다 | 이벤트 위조로 결제·예매 조작 |
| T6 | Access가 비밀번호 초기화·잠금 뒤에도 만료까지 유효 | 30분 | 탈취된 토큰이 최대 30분 유효 |
| T7 | 계정 기준 잠금 | 남의 이메일로 5회 실패시켜 잠금 가능 | 서비스 거부, 운영자 잠금 |

### D1 선택지

| | A. 네트워크만 신뢰 (SA, 채택) | B. Gateway가 서명한 내부 신원 | C. 서비스가 사용자 JWT 직접 검증 |
|---|---|---|---|
| 방식 | 헤더를 믿는다 | Gateway가 짧은 내부 토큰을 붙이고 서비스가 검증 | `Authorization`을 서비스까지 전달해 서비스가 검증 |
| T1·T2 방어 | 없음 | 있음 | 있음 |
| Gateway 침해 | 전부 위조 | 내부 키로 전부 위조 | 비대칭이면 위조 불가 |
| 요청당 비용 | 없음 | Gateway 서명 + 서비스 검증 | 서비스 검증 |
| SA 변경 | 없음 | 7-2 변경 | 7-2 변경 |

D2는 HS256 유지(단순, 검증자가 서명도 가능)와 RS256(발급자만 개인키, 서명은 로그인 때만이라 특성이 맞음) 중 후자를 AI가 추천했고, SA 7-3의 "MVP 이후"와 충돌해 철회했다.

## 인증 보강 항목

2026-10-27 사용자가 AI 의견대로 A1·A2·A4를 지금 적용하고 A3는 SA 합의 뒤로 두기로 했다. A1·A2·A4는 구현했다.

- A1(적용). `/internal/**`와 `/api/v1/internal/**`를 Gateway의 `AccessPolicy`에서 외부 차단(404)한다. 이후 서비스 간 HTTP가 생겨도 외부에서 닿지 않는다. 비용이 작고 SA와 충돌이 없다.
- A2(적용). common-web에 `@RequireRole` 어노테이션과 인터셉터를 둔다. 컨트롤러 안의 역할 확인 if를 없애고 "서비스(권한)" 계층(SA 9-2)을 한 곳에 모은다.
- A3(보류, SA 합의 뒤). 비밀번호 초기화·잠금 시 Redis에 `auth:revoked-after:{userId}`를 남기고 Gateway가 기존 블랙리스트 조회와 함께 `iat`와 비교한다. T6을 user-service 호출 없이 줄인다. SA 12-8의 Redis 키 표에 키가 하나 늘어 팀 확인이 필요하다.
- A4(적용). 서비스 포트 비공개 전제를 [워크스페이스 가이드](../guides/workspace.md)에 명시하고, 어기면 실패하는 `script/check-port-exposure.py`를 `check.sh`와 CI에 넣었다. SA 9-2 문서 자체는 바꾸지 않았다.

이번 범위에서 제외한 것: user-service 재확인 호출(SA 충돌), Spring Security WebFlux 전환(현재 필터로 충분하고 테스트가 있다).

## 열린 질문

- A3(`revoked-after`)를 SA 12-8 변경으로 팀에 제안할지.
- Gateway 기동 직후 첫 요청이 Redis 연결 수립 때문에 503이 나는 문제([ADR 0005](0005-auth-redis-fail-closed.md) 관찰)를 먼저 고칠지.
- Redis 비밀번호·ACL(T4)을 함께 할지.
- 로그인 남용 방어(T7: IP 기준 제한, 운영자 계정 예외)를 언제 다룰지.
