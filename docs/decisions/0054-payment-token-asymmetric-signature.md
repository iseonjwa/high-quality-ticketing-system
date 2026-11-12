# 0054. 결제 토큰은 비대칭 서명(ES256)으로 하고, 개인키는 예매 서비스에만 공개키는 결제 서비스에만 준다

- 상태: 제안 (ISE-245 contract. 예매 쪽 발급기 담당 확인 뒤 채택. SA 7-3의 "HMAC-SHA256"과 ADR 0025 결정 1·2·4·5의 키 부분 대체. SA 변경 요청 필요)
- 날짜: 2026-11-12
- 결정자: 안준현(사용자 결정: 11/12 결제 개선 방안을 전부 적용)
- 관련 AI 판단: [11/12 판단 일지](../ai-judgments/junhyun/daily/2026-11-12.md)(결제 개선 방안 전부 적용)
- 관련 결정: [0025](0025-payment-token-secret-injection.md), [0008](0008-internal-trust-and-auth-design.md), [0029](0029-payment-reservation-reconciliation.md)
- 대체하는 결정: [ADR 0025](0025-payment-token-secret-injection.md)의 키 형식·키 바이트 해석·주입 범위·시작 시 검증 중 **키에 관한 부분**. 토큰 검증 항목(용도·대상·만료·사용자·예매·금액), 노출 방지, 환경 분리, 저장소 규칙은 그대로다

## 맥락

결제 토큰은 "이 사용자가 이 예매를 이 금액으로 결제한다"는 예매 서비스의 확인서다. 예매 서비스가 서명하고 결제 서비스가 검증한다. 결제 서비스는 예매 DB를 읽지 못하므로 금액과 예매 ID를 이 토큰에서만 얻는다.

지금은 HS256이다(SA 7-3, ADR 0025). HMAC은 서명과 검증에 같은 키를 쓴다. 그래서 검증만 하면 되는 결제 서비스도 서명할 수 있는 키(`PAYMENT_TOKEN_SECRET`)를 갖는다. ADR 0025도 이 점을 적었다("HMAC은 검증 키로 서명도 할 수 있으므로 가진 서비스가 적을수록 위조 가능한 주체가 줄어든다"). 그래서 Gateway에는 키를 주지 않았지만, 검증하는 쪽은 줄일 수 없었다.

금액과 사용자가 어긋난 결제를 사후에 찾는 장치는 대사 RC-5·RC-6이다(ADR 0029). 이 결정은 그 앞단에서 "서명할 수 있는 곳"을 한 곳으로 줄인다.

## 결정

1. **알고리즘은 ES256(ECDSA P-256, SHA-256)이다.** 검증기는 알고리즘을 ES256으로 고정하고 토큰 헤더의 `alg`를 믿지 않는다. `none`, HS256, 다른 알고리즘은 거절한다.
2. **키를 나눈다.** 개인키 `PAYMENT_TOKEN_PRIVATE_KEY`는 `reservation-service`에만, 공개키 `PAYMENT_TOKEN_PUBLIC_KEY`는 `payment-service`에만 준다. compose의 `environment`에 각자 필요한 키만 넣는다. 결제 서비스가 뚫려도 토큰을 만들 수 없다.
3. **키 형식**: 개인키는 PKCS#8 DER을, 공개키는 X.509 SubjectPublicKeyInfo DER을 줄바꿈 없는 Base64 한 줄로 둔다. 환경변수와 `.env` 한 줄에 들어가고 PEM 머리말·줄바꿈 처리 실수가 없다.
4. **시작 시 검증(Fail-Fast)**: 두 서비스는 시작할 때 값이 있는지, Base64와 키 형식이 맞는지, 곡선이 P-256인지 검사하고 어긋나면 기동하지 않는다. 오류 메시지에는 키 값을 넣지 않는다.
5. **claim과 만료 규칙은 그대로다.** `sub`, `aud=payment-service`, `typ=payment`, `reservationId`, `amount`, `exp`, `jti`. 바뀌는 것은 헤더의 `alg`와 서명뿐이다. 프론트와 다른 서비스는 영향이 없다.
6. **전환은 한 번에 한다.** HS256을 함께 받는 기간을 두지 않는다. 단일 서버 compose 배포라 두 서비스가 같은 배포로 올라간다. 배포 순간 진행 중이던 결제 토큰(수명 최대 4분 50초)은 무효가 되고, 사용자는 선점 응답에서 새 토큰을 받는다. ADR 0025 결정 9의 키 교체 절차와 같은 영향이다.
7. **키 만들기**: `initLocalEnv`(`LocalEnvironment`)와 `script/deploy/init-env.sh`가 키 쌍이 없을 때 한 번 만든다. 두 키가 모두 없으면 한 쌍을 만든다. 두 키는 한 쌍이어야 하므로 **하나만 있으면 새로 만들지 않고 멈춰서 알린다**(하나만 새로 만들면 짝이 맞지 않아 모든 결제가 403이 된다). `PAYMENT_TOKEN_SECRET`은 더 쓰지 않는다. compose와 설정에서 지우고, 이미 있는 `.env`의 줄은 남아 있어도 읽지 않는다.
8. **배포 전제**: 이 변경이 든 릴리스 전에 서버에서 `init-env.sh`를 돌려 새 키 두 개를 만든다. 없으면 두 서비스가 기동하지 않고 `deploy.sh`가 직전 버전으로 되돌린다(안전한 쪽 실패).
9. **키 교체**: 공개키는 비밀이 아니라 교체가 쉬워진다. MVP는 자동 교체를 하지 않는다. 교체할 때는 새 키 쌍을 만들고 두 서비스를 함께 다시 띄운다(절차는 `manual-recovery.md`). 검증기가 공개키 두 개를 함께 받는 무중단 교체는 후속으로 둔다.

## 검토한 대안

- **HS256 유지**: 바꿀 것이 없다. 결제 서비스가 서명 가능한 키를 계속 갖는다.
- **RS256**: 가장 널리 쓴다. 같은 보안 수준에서 키(2,048비트 이상)와 서명(256바이트)이 커 토큰이 길어지고 서명이 느리다.
- **EdDSA(Ed25519)**: 빠르고 서명이 작다. 지금 쓰는 JWT 라이브러리(Nimbus)에서 추가 의존성(Tink)이 필요하다. ES256은 JDK만으로 된다.
- **결제가 승인 때 예매 서비스에 금액을 직접 묻는다**: 토큰이 필요 없어진다. 승인 경로에 동기 호출이 생겨 예매 서비스 장애가 결제 장애가 되고 승인 지연이 늘어난다. SA가 토큰을 고른 이유(결제가 예매를 부르지 않는다)와 어긋난다.
- **JWKS 엔드포인트로 공개키 배포**: 키 교체가 자동이 된다. 예매 서비스에 엔드포인트와 캐시, 결제 서비스에 조회·갱신이 생긴다. 서비스 둘, 키 하나인 지금은 환경변수로 충분하다.
- **HS256과 ES256을 함께 받는 전환 기간**: 진행 중 결제가 끊기지 않는다. 검증 경로가 둘이 되고 약한 쪽을 끄는 일을 잊을 수 있다. 재시작이 어차피 진행 중 요청을 끊으므로 얻는 것이 작다.

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 비대칭 서명 | 서명할 수 있는 곳이 예매 서비스 한 곳이 된다 | 서명·검증이 HMAC보다 느리다(아래 측정). 키가 둘이라 짝이 어긋나면 모든 결제가 403이다 |
| 한 번에 전환 | 검증 경로가 하나다 | 배포 순간 진행 중 결제 토큰이 무효가 된다 |
| 환경변수 주입 | 지금 구조 그대로다 | 키 교체는 두 서비스를 함께 다시 띄워야 한다 |

이 결정이 막지 않는 것: 예매 서비스 자체가 뚫리면 정당한 발급자가 토큰을 만든다. 결제 서비스는 여전히 Gateway가 넣은 사용자 헤더를 믿는다(ADR 0008). 그래서 대사 RC-5·RC-6은 계속 필요하다.

## 결과

- 예매: `JwtPaymentTokenIssuer`(키 읽기, ES256 서명), `config-repo/reservation-service.yml`.
- 결제: `PaymentTokenKey`·`PaymentTokenConfiguration`·`JwtPaymentTokenVerifier`(공개키, ES256 고정), `config-repo/payment-service.yml`, 테스트 토큰 도구(`PaymentTokenFixtures`).
- 공통: compose 3종, `.env.example`, `init-env.sh`, `LocalEnvironment`(`initLocalEnv`), `LocalApps`.
- 문서: `docs/design/payment/contracts.md`·`api.md`·`manual-recovery.md`(키 교체), `docs/design/reservation/contracts.md`, `deployment.md` 4절, SA 변경 요청.
- 측정(2026-11-12, ISE-245 구현): 같은 JVM에서 예열 2,000회 뒤 5,000회, Apple M4 Pro, OpenJDK 21의 로컬 개발 장비 값이다(배포 서버 `m7i.xlarge`와 다르다). 값은 테스트 출력(`PaymentTokenSigningTimeTest`, `PaymentTokenVerifyTimeTest`)이다.

  | 구간 | ES256 평균 | ES256 p99 | HS256 평균 | HS256 p99 |
  |---|---|---|---|---|
  | 서명(예매 `JwtPaymentTokenIssuer.issue`, claim 조립 포함. 비교는 예전 인코더) | 94.5µs | 124.6µs | 11.6µs | 25.1µs |
  | 검증(결제 `JwtPaymentTokenVerifier.verify`, 서명·claim 확인 전체. 비교는 예전 디코더의 서명 검증) | 278.8µs | 344.7µs | 8.1µs | 15.3µs |

  선점 한 번에 서명이 약 0.08ms, 승인 한 번에 검증이 약 0.27ms 더해진다. 승인 p99 0.33초 기준으로 0.1% 안팎이고 선점 경로의 비용도 작다. 다만 검증은 CPU를 쓰므로 승인을 초당 수백 건 받는 배포 측정에서는 CPU 사용을 함께 본다(추측, 측정 전). HS256 쪽 검증 값은 서명 검증만이라 검증기 전체와 직접 비교하면 ES256에 불리하게 보인다.

## 구현 메모

- 결정 1~9를 그대로 구현했다. 달라진 결정은 없다.
- 예매 발급기는 Spring의 `NimbusJwtEncoder` 대신 Nimbus `ECDSASigner`로 직접 서명한다. 인코더는 EC 개인키만으로 JWK를 만들려면 공개키가 필요해 서명 키 쌍을 다시 계산해야 하기 때문이다.
- 결제 검증기는 `NimbusJwtDecoder.withPublicKey`가 RSA 전용이라 `DefaultJWTProcessor` + `SingleKeyJWSKeySelector(ES256, 공개키)`로 만든다. 키 선택기가 헤더 `alg`가 ES256일 때만 키를 돌려주므로 HS256·`none`·공개키 바이트를 HMAC 키로 쓴 HS256 토큰이 모두 서명 검증 전에 거절된다(`JwtPaymentTokenVerifierTest`). 만료 검사를 빼는 기존 동작은 그대로다.
- 키 쌍은 `initLocalEnv`(`LocalEnvironment`)와 `script/deploy/init-env.sh`가 만든다. 하나만 있으면 새로 만들지 않고 없는 키를 알리며 실패한다. 임시 폴더에서 `init-env.sh`를 돌려 두 키가 한 쌍(공개키가 개인키에서 나온 것과 같음)임을 확인했다.

## 면접에서 설명할 한 줄

검증만 하는 서비스가 서명도 할 수 있는 대칭키를 갖고 있던 것을, 서명은 개인키를 가진 발급 서비스만 하고 검증은 공개키로 하게 바꿔 위조할 수 있는 곳을 한 곳으로 줄였다.
