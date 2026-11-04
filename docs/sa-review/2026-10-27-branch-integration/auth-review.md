# 공통·인증 통합 검토: 인증

인증의 기준 후보는 Junhyun 브랜치다. 회원·세션 업무까지 구현된 유일한 후보이며, Hyoseung의 Gateway 구현은 설정 분리와 테스트 관점에서 보완 자료로 활용할 수 있다.
이 문서는 코드와 새로 실행한 테스트의 관찰·추천이다. 팀 결정이나 병합 승인이 아니다. 실제 병합·코드 수정은 수행하지 않았다.

## 비교 시점과 기능 수준

| 이름 | 고정 SHA | 인증 수준 |
|---|---|---|
| Junhyun | `45874b7904d98b667aad0737500aa3631bdda3e1` | Gateway 인증, 회원가입·로그인·내 정보·Refresh 회전·로그아웃, 잠금·비밀번호 변경·관리자 초기화 |
| Hyoseung | `acb862788e80ec602837afee8954baa1be528c02` | Gateway JWT·블랙리스트·공개 경로·Redis 서킷, User는 시작 클래스 |
| Kwonwoo | `60994695c70fcb4e0c8c2ec11128fd7bb868de33` | gateway-edge·user-service 시작 클래스, 인증 업무 없음 |
| Kangmin | `965f000b6363170eab5761ea76def129a8394264` | Gateway 라우팅 골격, 인증 필터·회원 업무 없음 |
| Youngeun | `86e2e66dfca4036ea4eb10387b695e48b5a4191f` | 모듈·시작 클래스와 common-web/auth package-info, 인증 업무 없음 |

`git ls-tree -r --name-only <SHA>`와 각 브랜치의 production Java·설정 파일을 확인했다. 테스트·package-info·설계 문서만 있는 것을 업무 구현으로 세지 않았다.
Kwonwoo 근거: `60994695c70fcb4e0c8c2ec11128fd7bb868de33:gateway-edge/src/main/java/com/highquality/gatewayedge/GatewayEdgeApplication.java:6`, `60994695c70fcb4e0c8c2ec11128fd7bb868de33:user-service/src/main/java/com/highquality/userservice/UserServiceApplication.java:7`.

아래 J·H 근거는 다음 고정 커밋의 GitHub blob 링크다. 현 작업 디렉터리의 줄 번호와 혼용하지 않는다.

## Gateway·JWT 비교

| 항목 | Junhyun 사실 | Hyoseung 사실 | 통합 추천 |
|---|---|---|---|
| JWT 라이브러리·키 | Nimbus/Spring OAuth2 JOSE, UTF-8 원문 32바이트 이상, HS256 고정 | JJWT, JWT_SECRET을 Base64 decode, HMAC key로 검증 | J 계약 하나로 통일한다. 같은 환경변수 값을 이름만 바꾸면 서명 바이트가 달라진다 |
| 토큰 구분 | Access/Refresh 키·audience 분리, `iss`, `exp`, `iat`, 양수 Long `sub`, `jti` 존재, `token_type`, USER/ADMIN 검사 | 서명·있는 exp의 만료, 숫자 문자 sub·role·비어 있지 않은 jti 검사. issuer/audience/token_type/iat/exp 필수 여부는 검사하지 않음 | H verifier를 그대로 섞지 않는다. 입장·결제 토큰까지 같은 서명키에 넣지 않는다 |
| JOSE typ | 발급 헤더 `typ=JWT`; 업무 종류는 payload `token_type=access/refresh` | 업무 토큰 종류 검사 없음 | `typ`와 `token_type`을 동일 용어로 부르지 않는다. 입장·결제 토큰 규칙과 별도 합의한다 |
| 헤더 위조 방지 | 전달 시 X-User-Id·X-User-Role 제거 후 검증 값만 set. 공개 API는 제거만 수행 | 인증 전 두 헤더 제거 후 검증 값 전달 | 두 구현 모두 방향이 맞다. J의 공통 IdentityHeaders와 계약을 유지한다 |
| 관리자 | `/api/v1/admin/**` Gateway ADMIN 검사 + 초기화 컨트롤러 `@RequireRole(ADMIN)` | Gateway는 역할 유효성만 검사. 서비스에 관리자 인가 책임을 위임하나 User API는 미구현 | J의 계층별 인가를 유지한다 |
| 내부 API | `/internal/**`, `/api/v1/internal/**`를 외부 404 처리 | 별도 내부 경로 차단 정책 없음. 다른 경로처럼 인증 요구 | J 유지. 임의 새 내부 경로는 추가 계약 필요 |
| 공개 공연 API | GET concerts와 concerts/*만 공개 | GET concerts/** 전체 공개 | 하위 회차·좌석 API까지 공개되지 않도록 범위를 명시한다 |
| 공개 비밀번호 변경 | POST `/api/v1/auth/password-change`를 공개하고 현재 비밀번호로 검증 | 공개 목록에 없음 | J 업무를 가져오면 공개 목록도 함께 가져온다 |
| 관리자 라우트 | admin/users를 user-service로 먼저 라우팅 | `/api/v1/admin/**` 전체 reservation-service로 라우팅 | J 순서를 유지한다. H 설정으로 덮으면 초기화가 잘못 전달된다 |
| 블랙리스트 장애 | 100ms + auth-redis 서킷, 실패 503·Retry-After: 1 | Redis 100ms TimeLimiter·서킷, 실패 false로 통과 | 팀 선택 필요. 실패 처리 코드를 양쪽에서 섞지 않는다 |
| 기동 준비 | Redis PING 2초 제한·200ms 간격 2회 재시도 뒤 기동 실패 | 별도 warmup 없음 | J 정책의 기동 가용성 영향도 팀에 알린다 |

근거:

- [J JWT 검증 24–58](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/common-auth/src/main/java/iseonjwa/common/auth/TokenDecoders.java#L24), [J 종류 계약 4–9](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/common-auth/src/main/java/iseonjwa/common/auth/TokenType.java#L4), [J 발급·키 분리 36–53, 90–103](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/infrastructure/security/JwtTokens.java#L36).
- [H JWT 검증 29–58](https://github.com/iseonjwa/high-quality-ticketing-system/blob/acb862788e80ec602837afee8954baa1be528c02/gateway/src/main/java/com/iseonjwa/gateway/auth/JwtVerifier.java#L29). HS256이라는 주석과 달리 parser에서 알고리즘을 HS256 하나로 제한하는 설정은 없다. 실제 다른 알고리즘 수용 여부는 키 길이·라이브러리 규칙에 좌우되므로 실행 미검증이다.
- [J 경로 판정 17–46](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/gateway/src/main/java/iseonjwa/gateway/security/AccessPolicy.java#L17), [J 필터 49–105](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/gateway/src/main/java/iseonjwa/gateway/security/GatewayAuthenticationFilter.java#L49), [H 필터 46–75](https://github.com/iseonjwa/high-quality-ticketing-system/blob/acb862788e80ec602837afee8954baa1be528c02/gateway/src/main/java/com/iseonjwa/gateway/auth/AuthenticationFilter.java#L46).
- [J 라우트·서킷 설정 11–42](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/config-repo/gateway.yml#L11), [H 라우트·공개 경로·서킷 설정 20–52](https://github.com/iseonjwa/high-quality-ticketing-system/blob/acb862788e80ec602837afee8954baa1be528c02/config-repo/gateway.yml#L20).
- [H fail-open 35–41](https://github.com/iseonjwa/high-quality-ticketing-system/blob/acb862788e80ec602837afee8954baa1be528c02/gateway/src/main/java/com/iseonjwa/gateway/auth/TokenBlacklist.java#L35), [H Redis 설정 분리 20–39](https://github.com/iseonjwa/high-quality-ticketing-system/blob/acb862788e80ec602837afee8954baa1be528c02/gateway/src/main/java/com/iseonjwa/gateway/config/RedisCircuitBreakerConfig.java#L20), [J warmup 18–36](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/gateway/src/main/java/iseonjwa/gateway/security/GatewayConfiguration.java#L18), [J Retry-After 23–29](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/gateway/src/main/java/iseonjwa/gateway/security/GatewayErrorWriter.java#L23).

## User 업무·DB·Redis 계약

Junhyun의 이메일은 strip·소문자로 정규화하고 DB UNIQUE로 중복을 거절한다. BCrypt와 UTF-8 72바이트 상한을 적용하며, 가입 DTO와 새 비밀번호는 최소 8자를 요구한다.
로그인 실패 횟수는 native UPDATE로 올리고 5회부터 잠근다. 없는 이메일에도 dummy BCrypt를 수행한다. 성공 로그인 시 실패 횟수를 지운다.

비밀번호 비교는 행 잠금 밖에서 하고, 로그인·Refresh·변경·초기화의 세션 변경은 PostgreSQL 사용자 행 잠금 안에서 수행한다. 비밀번호 해시 변경과 잠금 여부를 발급 직전에 다시 검사한다.
Refresh는 `auth:refresh:{userId}` 하나에 SHA-256 digest로 저장하므로 새 로그인은 이전 Refresh를 대체한다. 회전은 이전 digest가 같을 때만 교체하는 Lua CAS이며 기본 TTL은 14일이다.
로그아웃 Lua는 Refresh 삭제와 `auth:blacklist:{jti}` 등록을 함께 수행한다. 블랙리스트 TTL은 Access 남은 시간이며 Access 기본 유효 기간은 30분이다.

초기화는 ADMIN에게만 허용하며 임시 비밀번호를 no-store 응답으로 한 번 반환한다. 잠금을 풀고 비밀번호 변경을 요구하며 Refresh를 폐기한다.
공개 비밀번호 변경은 이메일·현재 비밀번호를 검증하고 새 해시 저장 전 Refresh를 폐기한다. 초기화·변경 뒤에도 이미 발급한 Access 자체를 모두 폐기하는 구현은 없다.

근거:

- [J 업무 60–135](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/application/AuthService.java#L60), [J 인증·현재 상태 재검사 165–208](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/application/AuthService.java#L165), [J 비밀번호·정규화 219–237](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/application/AuthService.java#L219).
- [J Redis 키·Lua 21–35](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/infrastructure/security/RedisSessionStore.java#L21), [J 저장·회전·폐기·해시 43–106](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/infrastructure/security/RedisSessionStore.java#L43).
- [J 행 잠금·실패 횟수 SQL 17–51](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/infrastructure/persistence/SpringDataUserRepository.java#L17), [J 초기 스키마 1–13](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/resources/db/migration/V1__users.sql#L1), [J 잠금 스키마 1–4](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/resources/db/migration/V2__login_security.sql#L1).
- [J 입력 DTO 10–24](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/presentation/AuthDtos.java#L10), [J 관리자 인가·no-store 16–33](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/presentation/AdminUserController.java#L16), [J TTL·실패 한도 설정 13–22](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/config-repo/user-service.yml#L13).

## 신뢰 경계와 남은 결함·한계

| 분류 | 사실·영향 | 제안 |
|---|---|---|
| SA에 따른 신뢰 전제 | common-web은 헤더 형식만 확인하고 서비스에서 JWT를 재검증하지 않는다. 내부 서비스에 직접 접근하면 유효한 형식의 사용자 헤더를 신뢰한다 | 외부 포트 차단·내부 네트워크 전제를 실제 배포에서 확인한다. 서비스 JWT 재검증·RS256 도입을 이번 통합에 몰래 추가하지 않는다 |
| 합의 필요 | H fail-open이면 Redis 장애 때 로그아웃 Access도 통과할 수 있다. J fail-closed면 모든 보호 요청이 503이 된다 | live Notion #48의 팀 잠정은 fail-open이다. J fail-closed는 별도 브랜치 선택이므로 통합에 자동 승계하지 않고 팀에서 재검토한다 |
| 수정 후보 | J 만료 JWT도 일반 UNAUTHORIZED로 응답하며 SA의 TOKEN_EXPIRED 구분을 잃는다 | 오류 계약을 통일하고 만료 분기 회귀를 추가한다 |
| 수정 후보 | H는 필수 exp 누락을 거절하지 않는다. iss·aud·업무 종류도 확인하지 않는다 | J decoder로 대체한다. H verifier를 가져오는 선택이면 누락·다른 토큰 종류 거절 테스트부터 보강한다 |
| 수정 후보 | J jti는 null만 거절하므로 빈 문자열 jti는 통과할 수 있다 | 발급기가 UUID를 쓰더라도 검증 계약은 nonblank로 좁히는 안을 검토한다 |
| 미구현 | 두 Gateway 모두 사용자 단위 요청 제한 구현은 없다 | SA H1 작업과 담당을 따로 추적한다. M1 인증 구현을 H1 전체 완료로 쓰지 않는다 |
| 의미 확인 | 한 사용자당 Refresh 1개, 로그아웃은 현재 Access jti 하나와 사용자 Refresh를 폐기한다 | 다중 기기 세션·모든 Access 즉시 폐기 요구가 있는지 확인한다 |
| 알려진 한계 | DB 잠금과 Redis 명령은 분산 트랜잭션이 아니다. Redis 변경 뒤 DB 커밋 실패나 타임아웃 실행 여부 불명확이 남는다 | 로그인 실패 후 재로그인, Refresh 실패 후 재인증 복구 의미를 문서화한다. “모든 경합 완전 해결”로 표현하지 않는다 |
| 기동 영향 | J의 기존 ADMIN이 잠겨 있으면 seedAdmin이 재기동 때 실패 횟수·잠금을 푼다 | 운영자 복구 정책으로 팀에 명시한다 |

근거: [J 헤더 신뢰 16–42](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/common-web/src/main/java/iseonjwa/common/web/GatewayIdentityFilter.java#L16), [J 만료 오류 경로 63–67](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/gateway/src/main/java/iseonjwa/gateway/security/GatewayAuthenticationFilter.java#L63), [J ADMIN seed 144–162](https://github.com/iseonjwa/high-quality-ticketing-system/blob/45874b7904d98b667aad0737500aa3631bdda3e1/user-service/src/main/java/iseonjwa/user/application/AuthService.java#L144).
설계 대조: 저장소 `docs/core/SA MASTER.md` 215, 333–334, 476, 697, 710–723, 865, 1428–1436행. 부모 검토에서 확인한 live Notion #21·#48·#56·#57은 잠정이며, #48은 fail-open이다. 자세한 원문·출처는 같은 폴더의 `source-notes.md`를 따른다. 저장소 사본의 Junhyun fail-closed 반영이 팀 합의 변경을 뜻하지 않는다.
J ADR 0009의 서비스 JWT 직접 검증 선택은 같은 커밋에서 철회 상태다. 현재 동작을 그 ADR의 최초 제안으로 오해하면 안 된다.

## 팀 논의에서 분리할 판단

현상은 J의 fail-closed와 H의 fail-open이고, 팀 잠정은 live #48의 fail-open이다. 검토 추천은 이 차이를 통합 전 의제로 올리는 것이다. 승인 없이 어느 쪽도 팀 확정으로 바꾸지 않는다.
#48의 “별도 결제 토큰으로 피해 제한”은 결제 승인 경로의 일부 방어 근거다. 로그아웃 Access를 가진 호출자의 내 정보 조회·예약 생성/취소·관리자 API 사용까지 막는 근거는 아니다. Gateway가 중단하는 조회·예매 가용성 비용과 실제 보호 API별 위험을 함께 비교해야 한다.
비밀번호 변경·초기화도 현재는 Refresh만 폐기한다. fail-closed를 택해도 블랙리스트에 없는 기존 Access는 막지 못하므로 두 문제를 같은 해결책으로 표현하지 않는다.

현재 J는 JOSE header `typ=JWT`, payload `token_type=access/refresh`, 서로 다른 `aud`를 발급한다. 업무 목적의 판정은 `token_type`·aud·키 분리로 수행한다. header typ에 access/refresh를 넣어 구별하는 구현은 아니다.
SA #56의 입장·결제 토큰 “typ claim”이 JOSE header `typ`인지 payload 값인지 먼저 정해야 한다. Access/Refresh와 입장/결제 토큰은 별도 계약이며, 현재 J의 payload 이름을 입장·결제 계약에 이미 합의된 값으로 간주하면 안 된다. RFC typ 준수 주장을 할 때는 위치와 실제 decoder 검증 동작을 함께 확인해야 한다.

## 가져올 변경의 분류

| 분류 | 대상 | 이유·조건 |
|---|---|---|
| 그대로 가져올 후보 | J TokenType·TokenDecoders·JwtTokens, DB V1/V2, AuthService·RedisSessionStore·컨트롤러·관련 테스트 | 가장 넓은 실제 업무 범위와 공통 JWT 계약이다. 아래 수정·논의 항목을 별도 반영한다 |
| 그대로 가져올 후보 | J AccessPolicy와 변형 경로 테스트, 사용자 헤더 제거, 관리자 이중 인가, user 라우트 선순위 | 정책·라우팅·인가가 함께 맞아야 한다 |
| 수정하여 활용 | H RedisGuardProperties·RedisCircuitBreakerConfig의 설정 분리 | J의 auth-redis 이름·최소 호출 수 20·100ms·실패 정책과 충돌 없이 하나로 통합한다. 같은 기능의 Java/YAML 중복 정본은 만들지 않는다 |
| 수정하여 활용 | H GatewayRoutingTest·JwtVerifierTest·TokenBlacklistTest의 시나리오 | 패키지·응답·토큰 계약에 맞춰 필요한 누락 시나리오만 가져온다. 기대 결과가 fail-open인 검증은 정책 결정 후 고친다 |
| 제외 | H JwtVerifier·AuthenticationFilter·TokenBlacklist를 J와 동시 등록 | GlobalFilter 중복 실행, 키 해석 차이, fail-open/closed 상충을 만든다 |
| 제외 | Kwonwoo gateway-edge 인증 골격·User 시작 클래스를 J 기능 대체물로 채택 | 추가 인증 업무가 없어 기준을 교체할 이익이 없다 |
| 팀 논의 | Redis 장애 정책·공개 GET 범위·비밀번호 부가 기능 우선순위·Access 폐기 범위 | 가용성·보안·SA 범위가 바뀌므로 코드 스타일 판단으로 정하지 않는다 |

비밀값 관련 정적 확인 범위는 인증 production 설정과 발급·검증 코드다. J는 JWT_ACCESS_SECRET/JWT_REFRESH_SECRET, H는 JWT_SECRET 자리표시자를 사용한다.
실제 비밀값은 출력하거나 복사하지 않았다. 모든 Git 이력의 비밀값 유무를 전수 검사했다는 의미는 아니다. 테스트 전용 키·비밀번호를 운영 값으로 재사용하지 않는다.

## 이번 검증

현재 checkout을 이동하지 않고 `/tmp/ise-auth-review.Rx4S5K`에 다음 커밋을 `git archive`로 추출했다.

```bash
git archive 45874b7904d98b667aad0737500aa3631bdda3e1 | tar -x -C /tmp/ise-auth-review.Rx4S5K
JAVA_HOME=/Users/alloy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home \
  ./gradlew :common-auth:test :common-web:test :gateway:test :user-service:test \
  --tests 'iseonjwa.user.AuthConfigurationTest' --no-daemon
```

2026-10-27 새 실행 결과는 `BUILD SUCCESSFUL in 20s`, 21개 task 실행이다. JUnit XML을 직접 합산하면 77건, 실패·오류·skip 0건이다.

| 범위 | 실행 건수 | 확인한 범위 |
|---|---:|---|
| common-auth | 8 | JWT 정상·만료·다른 키/issuer/audience/종류/역할·짧은 키 |
| common-web | 19 | 사용자 헤더 형식·역할 인가·응답 계약 |
| gateway | 42 | 필터·Redis 오류 응답·기동 warmup·경로 판정, mock 기반 |
| user-service | 8 | AuthConfigurationTest 설정 검증만 실행 |

Gradle `--tests`는 마지막 `:user-service:test`에 적용되었다. 앞의 세 모듈은 전체 test가 실행되었다.
보고서·XML은 임시 디렉터리 각 모듈의 `build/reports/tests/test`, `build/test-results/test`에 있다. 기존 작업 디렉터리의 과거 결과를 사용하지 않았다.

실제 PostgreSQL·Redis가 필요한 AuthIntegrationTest는 이번에 실행하지 않았다. 기본 대상은 localhost:25433/user_test·26379이며 실제 DB·Redis를 사용한다. 기존 인프라 공유 여부를 확정하지 않고 데이터를 바꾸거나 스택을 조작하지 않았다.
해당 클래스에는 현재 20개 테스트가 있으며 Refresh 동시 재사용, 잠금·비밀번호 변경·초기화 경합 회귀가 포함되어 있다. 이는 소스 존재 확인이며 이번 실행 통과가 아니다.
전체 Gateway→서비스 HTTP, Redis pause·복구, 실제 라우트·포트 노출, 운영 배포·부하 성능, Hyoseung 인증 테스트는 이번 담당 범위에서 실행 미확인이다.
기존 Docker pause·stop·시작·중단은 수행하지 않았다. 병합 뒤에는 격리 인프라를 준비해 이 20건과 시스템 테스트를 새로 실행해야 한다.
