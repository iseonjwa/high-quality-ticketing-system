# 0024. 결제 토큰 키는 환경변수로 주입하고 시작할 때 검증하며, 키 바이트는 기존 JWT 키와 같은 방식으로 해석한다

- 상태: 제안 (SA 변경 요청 필요. 2026-10-28 개정, 키 해석 선택은 AI 판단이라 사용자 확인 필요)
- 날짜: 2026-10-27
- 결정자: 안준현
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0015](0015-payment-declined-token-reuse.md), [0008](0008-internal-trust-and-auth-design.md), [0013](0013-single-common-module.md), [0018](0018-payment-new-key-existing-payment.md)
- 불변 조건: INV-3, INV-9([invariants.md](../design/payment/invariants.md))

## 맥락

SA 7-3은 결제 토큰을 HMAC-SHA256으로 서명하고 키 `PAYMENT_TOKEN_SECRET`을 입장 토큰 키(`ENTRY_TOKEN_SECRET`)와 분리한다. 발급은 Reservation, 검증은 Payment가 하고, Gateway는 이 키를 갖지 않는다(HMAC은 검증 키로 서명도 할 수 있으므로 가진 서비스가 적을수록 위조 가능한 주체가 줄어든다). 저장소 설정(`config-repo`, `compose*.yml`, `.env.example`)에는 이 키가 아직 없다.

기존 비밀값 방식: `./gradlew initLocalEnv`가 `.env`에 개인 개발값(`JWT_ACCESS_SECRET` 등)을 만들고, `compose.yml`이 `${NAME:?메시지}`로 필수 주입을 강제하며, `config-repo/*.yml`은 `${NAME}` 자리표시자만 둔다. `.env`는 Git 제외 대상이다.

### 실제 공통 코드와 원안의 차이 (2026-10-28 확인)
- `workspace-tools`의 `LocalEnvironment`는 `JWT_ACCESS_SECRET`·`JWT_REFRESH_SECRET`을 `SecureRandom` 32바이트를 `HexFormat`으로 인코딩한 64자 소문자 16진수로 만든다. `PAYMENT_TOKEN_SECRET`은 아직 목록(`KEYS`)에 없다.
- `common/.../auth/TokenDecoders.signingKey(secret)`는 이 문자열을 **16진수로 디코딩하지 않고** `secret.getBytes(UTF_8)`로 쓰며 32바이트 미만이면 거절한다. 64자 16진수 문자열은 64바이트 HMAC 키가 된다. Gateway·User의 Access·Refresh JWT가 이 함수를 쓴다.
- 같은 클래스는 `JwtTimestampValidator(Duration.ZERO)`로 `exp` 허용 오차를 0초로 둔다.
- 2026-10-27 원안은 "16진수 형식, 디코딩한 길이 32바이트"와 "`exp` 소규모 허용 오차"를 적어 위 두 가지와 어긋났다(Opus 검토 L4). 발급기(Reservation)가 공통 함수를 쓰고 검증기(Payment)가 16진수를 디코딩하면 같은 키 문자열로 서로 다른 바이트를 얻어 모든 결제 토큰이 검증에 실패한다.

## 결정

기존 Docker Compose 환경변수 주입 방식을 유지하고 다음 조건을 적용한다.

1. **키 형식**: 암호학적으로 안전한 난수 32바이트를 소문자 16진수 문자열(64자)로 인코딩해 `PAYMENT_TOKEN_SECRET`으로 관리한다(기존 JWT 키와 같은 생성 방식).
2. **키 바이트 해석**: HMAC 키는 이 문자열의 **UTF-8 바이트(64바이트)를 그대로** 쓴다. 16진수로 디코딩하지 않는다. 발급기와 검증기는 모두 `TokenDecoders.signingKey(secret)`로 키 바이트를 얻는다. 같은 함수를 쓰게 해 두 서비스의 해석이 어긋날 여지를 없앤다. 엔트로피는 생성 단계의 32바이트(256비트)로 디코딩 여부와 무관하다.
3. **생성**: `initLocalEnv`는 키가 `.env`에 없을 때 최초 한 번만 생성하고, 다시 실행해도 기존 키를 유지한다(다른 키 재생성은 옵션으로 명시할 때만). `LocalEnvironment.KEYS`에 `PAYMENT_TOKEN_SECRET`을 추가하고 JWT 키와 같은 `randomHex(random, 32)`를 쓴다.
4. **주입 범위**: 같은 키를 `reservation-service`와 `payment-service`에만 주입한다. Gateway와 다른 서비스에는 전달하지 않는다(compose `environment`에 넣지 않음). `compose.local.yml`, `compose.jvm.yml`, `compose.test.yml`도 같은 규칙을 따른다.
5. **시작 시 검증(Fail-Fast)**: 두 서비스는 시작할 때 (a) 값이 존재하는지, (b) 정확히 64자이고 `[0-9a-f]`만으로 이뤄졌는지 검사하고, (c) `TokenDecoders.signingKey`로 키 바이트를 만든다(32바이트 이상 검사 포함). 하나라도 어긋나면 기동을 실패시킨다. 오류 메시지에는 키 값을 넣지 않고 어느 조건이 틀렸는지만 쓴다. (b)는 각 서비스에서 한다. 형식 검증 함수를 `common.auth`에 두어 공유하는 것은 [0013](0013-single-common-module.md)의 규칙 안에서 가능한 후속 개선이다(공통 모듈 소유 팀과 협의, S-13).
6. **저장소 규칙**: 실제 키는 `.env`와 Git 관리 대상에서 제외한다. `.env.example`에는 이름과 빈 값만, `config-repo`에는 설정 이름과 `${PAYMENT_TOKEN_SECRET}` 자리표시자만 둔다.
7. **환경 분리**: 로컬, 테스트, 운영은 서로 다른 키를 쓴다. EC2 배포에서는 운영용 키를 별도로 주입하고 안전하게 보관한다(보관 방법은 배포 이슈에서 정한다. 저장소나 이미지에 넣지 않는다).
8. **노출 방지**: 키가 로그, 예외 메시지, Actuator(`env`·`configprops`) 설정 조회 결과, 지표·트레이스 태그에 나타나지 않게 한다. Actuator는 민감 속성을 마스킹하는 설정을 쓰고, 관리 포트는 이미 내부에 한정돼 있다. 테스트로 키 문자열이 응답과 로그에 없음을 확인한다.
9. **키 회전**: MVP에서는 자동 회전을 하지 않는다. 키가 유출됐을 때의 재발급과 기존 토큰 무효화 절차(두 서비스에 새 키를 배포하고 재시작하면 이전 키로 서명한 토큰은 검증에 실패한다. 결제 토큰의 수명이 선점 만료 − 10초(최대 4분 50초)라 진행 중 결제는 다시 선점 응답에서 새 토큰을 받는다)를 문서화한다(`docs/design/payment/manual-recovery.md`).

### 토큰 검증 항목
서명만 확인하지 않고 다음을 함께 검증한다(SA 7-3을 구현 수준으로 구체화).

| 항목 | 검증 |
|---|---|
| 길이 | 4,096자 이하`[가정]`. 넘으면 파싱하지 않고 거절 |
| 서명 | HMAC-SHA256, 키 바이트는 위 결정 2. 알고리즘은 고정(토큰 헤더의 `alg`를 믿지 않고 `none`·다른 알고리즘 거절). 서명 비교는 상수시간이다. 라이브러리(Nimbus)의 비교가 상수시간인지 구현 때 확인하고`[가정]`, 직접 비교하면 `MessageDigest.isEqual`을 쓴다 |
| 용도 | `typ=payment` |
| 대상 | `aud=payment-service` |
| 만료 | `exp`, 허용 오차 0초(`TokenDecoders`와 같음). 발급·검증이 같은 호스트 시계를 쓰고, 선점 만료와의 10초 간격(SA 7-3)이 이미 여유라서 허용 오차를 두면 그 간격을 갉아먹는다. 만료 토큰은 신규 승인에 쓰지 않고, 만료 뒤 24시간 안에는 기존 결제 조회에만 쓴다(0018) |
| 사용자 | `sub`가 인증된 `X-User-Id`와 같다 |
| 예매 | 토큰의 예매 ID를 결제 대상으로 쓴다. 같은 예매에 DECLINED·REFUNDED 결제가 있으면 거절한다(0015) |
| 금액 | 요청 금액이 토큰 금액과 같다 |
| 식별 | `jti`는 로그·추적용(폐기 목록 없음, SA 7-3) |

요청 본문의 `method`는 `CARD`만 받는다(`BILLING`은 M4 자동결제 이벤트 전용, [api.md](../design/payment/api.md)). claim 이름은 형식을 소유한 반효승이 통보한다(U1). 검증기는 claim 이름을 한 곳의 상수로 둔다.

## 검토한 대안

- **Docker secrets 파일**: 키가 프로세스 환경에 노출되지 않아 보안은 낫다. 현재 compose·Config Server 구조와 다른 방식이라 설정과 문서가 늘고 EC2 배포도 맞춰야 한다. 운영 배포 시점에 다시 검토한다.
- **Config Server에 저장**: 설정이 한 곳에 모이지만 `config-repo`는 Git 저장소라 비밀값을 둘 수 없다.
- **16진수 디코딩 32바이트로 통일(원안)**: 키 바이트가 난수 그대로라 의미가 깔끔하다. 결제 토큰만 다른 해석을 쓰게 되고, 공통 함수를 그대로 쓰는 쪽과 어긋나는 실수가 생기기 쉽다. JWT 키까지 디코딩으로 바꾸려면 Gateway·User 코드와 테스트를 고쳐야 하고 기존 `.env`의 키 의미가 바뀐다. 보안 이득(엔트로피)은 같다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 환경변수 주입 | 기존 `JWT_*_SECRET`과 같은 방식이라 설정·문서가 늘지 않는다 | 프로세스 환경에 키가 있다. 컨테이너 환경 조회 권한이 곧 키 접근이다 |
| 두 서비스에만 주입 | Gateway·다른 서비스가 토큰을 만들 수 없다 | 키 변경 때 두 서비스를 함께 배포해야 한다 |
| UTF-8 바이트 해석(기존과 같음) | 공통 함수 하나로 발급·검증 해석이 일치한다. 공통 모듈 변경이 없다 | 키 바이트가 "난수 32바이트"가 아니라 "16진수 문자 64바이트"라 직관과 다르다(문서로 고정) |
| `exp` 허용 오차 0초 | 선점 만료와의 10초 간격을 그대로 지킨다 | 호스트가 나뉘면 시계 차이로 경계 토큰이 거절될 수 있다(현재는 EC2 1대) |
| 자동 회전 제외 | 구현이 단순하다 | 회전은 수동 절차다. 회전 중 잠깐 토큰 검증이 실패할 수 있다 |

## 결과

- 변경 대상: `.env.example`, `workspace-tools`의 `LocalEnvironment`(`KEYS`와 생성 규칙), `compose.yml`·`compose.local.yml`·`compose.jvm.yml`·`compose.test.yml`(두 서비스에만), `config-repo/payment-service.yml`·`reservation-service.yml`(자리표시자).
- 공통 모듈(`TokenDecoders`)은 고치지 않는다. 형식 검증 함수 공유는 후속 개선이다.
- Reservation 서비스 쪽 변경은 반효승·권우 담당 코드에 걸치므로 통보하고 합의한다(발급기는 `TokenDecoders.signingKey`로 키 바이트를 만든다).
- SA 변경 요청: S-13(키 형식과 해석, 시작 검증, 주입 범위, `exp` 허용 오차, 길이 상한, 키 유출 시 절차).
- 테스트: 키 없음·형식 오류(길이, 대문자·비16진수) 시 기동 실패(N12), 발급기 키 바이트와 검증기 키 바이트 일치(같은 문자열로 서명·검증 왕복), 서명 변조·`alg` 변조·`typ`·`aud`·`exp`·`sub`·금액 불일치 거절, 길이 초과 거절, 만료 경계(허용 오차 0초), 키가 로그·Actuator에 없음, `initLocalEnv` 재실행 시 키 유지, Gateway 컨테이너 환경에 키 없음.

## 개정 이력

- 2026-10-28: 실제 `TokenDecoders`·`LocalEnvironment` 동작과 원안의 차이를 맥락에 적고, 키 바이트 해석을 기존 JWT 키와 같은 UTF-8 바이트로 정했다(검토 L4, 사용자 지정 수정). `exp` 허용 오차를 0초로, 상수시간 비교·토큰 길이 상한·`alg` 고정·`CARD`만 허용을 명시했다. 시작 검증을 "디코딩 길이 32바이트"에서 "64자 소문자 16진수 + `signingKey` 검사"로 바꿨다. 제목을 바꿨다(이전: "결제 토큰 키는 환경변수로 주입하고 시작할 때 검증한다").

## 면접에서 설명할 한 줄

결제 토큰 키는 발급(Reservation)과 검증(Payment)에만 주고 Gateway에는 주지 않아 위조 가능한 주체를 줄였고, 키 문자열을 바이트로 바꾸는 규칙을 기존 공통 함수 하나로 고정해 두 서비스의 해석이 어긋나 모든 토큰이 실패하는 사고를 설계 단계에서 막았다.
