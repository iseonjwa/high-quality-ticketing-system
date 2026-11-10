---
title: Gateway에 벌크헤드 설정이 없으면 기본값이 걸리나
date: 2026-11-09
updated: 2026-11-09
asker: kangmin
level: 중급
tags: [bulkhead, gateway, resilience4j, spring-cloud-circuitbreaker, default-config, load-test]
---

# Gateway에 벌크헤드 설정이 없으면 기본값이 걸리나

## 질문
11/9 밤 측정 중 영은님이 "gateway 벌크헤드를 바꾸거나 끄고 재도 되나"를 물었다. gateway에 벌크헤드가 없지 않나 되묻자 "없으면 기본값 아니냐", "Grafana에 bulkhead 여유 값 지표가 있다는 것 자체가 걸려 있다는 증거"라는 답이 왔다. 맞나?

## 짧은 답
- **"없으면 기본값"은 맞다.** gateway의 인증 Redis 조회(`auth-redis`)에는 라이브러리가 기본으로 거는 벌크헤드가 있다. 동시 25, 대기 0이다. 저장소 설정(`config-repo/gateway.yml`)에는 한 줄도 없어서 코드·설정만 검색하면 보이지 않는다.
- **"Grafana 지표가 증거"는 근거가 다르다.** 팀 대시보드의 벌크헤드 패널은 모두 `application="payment-service"`로 거른다. 그 패널은 PG 승인 벌크헤드(`pg-approve` 10, `pg-recovery` 2)를 보여 줄 뿐 gateway 벌크헤드의 증거가 아니다.

## 자세히

### 1. 기본 벌크헤드가 걸리는 경로 (라이브러리 바이트코드로 확인)
| 단계 | 확인한 것 |
|---|---|
| 의존성 | gateway의 `spring-cloud-starter-circuitbreaker-reactor-resilience4j`(5.0.3)가 `resilience4j-bulkhead` 2.3.0과 `resilience4j-spring-boot3`를 함께 가져온다(`./gradlew :gateway:dependencies`) |
| 자동 설정 | `ReactiveResilience4JAutoConfiguration$Resilience4jBulkheadConfiguration`이 `ReactiveResilience4jBulkheadProvider`를 만든다. 조건은 `Bulkhead` 클래스가 있고 `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled`가 꺼져 있지 않은 것이다(기본 `true`, 없으면 켜짐) |
| 적용 | `ReactiveResilience4JCircuitBreaker.run()`이 provider가 있으면 먼저 `decorateMono`로 벌크헤드를 씌우고, 그 바깥을 서킷 브레이커로 감싼다 |
| 이름 | `breakers.create("auth-redis")`는 id와 그룹 이름이 같아 벌크헤드 이름도 `auth-redis`다 |
| 값 | 따로 정하지 않으면 `BulkheadConfig.ofDefaults()`: `maxConcurrentCalls` 25, `maxWaitDuration` 0초 |

### 2. 무엇을 막고, 걸리면 어떻게 되나
- 대상: 로그인이 필요한 요청마다 하는 로그아웃 토큰 조회 `redis.hasKey("auth:blacklist:<jti>")` 하나다(`GatewayAuthenticationFilter.isRevoked`). 공개 경로와 서비스로 넘기는 요청 자체에는 걸리지 않는다.
- 동시 조회가 25개를 넘으면 기다리지 않고 바로 거절한다. 거절은 `onErrorMap`에서 `AuthDependencyUnavailable`로 바뀌어 **503 `SERVICE_UNAVAILABLE` "일시적으로 인증을 확인할 수 없습니다"**가 된다.
- 벌크헤드가 서킷 브레이커 안쪽에 있으므로 거절도 브레이커의 실패로 셀 수 있다. 그러면 실패율 50%(20건 기준)를 넘어 OPEN이 되고, 10초 동안 인증 경로 전체가 같은 503을 낸다. 거절이 실패로 세어지는지는 브레이커의 기록 예외 설정에 달려 있고, 실제로 그렇게 되는지는 확인 필요다.
- Redis가 1ms에 답하면 동시 25로 초당 수천 건을 처리한다. Redis가 느려지거나 이벤트 루프가 밀려 조회가 길어지면 25가 먼저 찬다. L1의 원인 모를 503 후보 중 하나다(확인 필요).

### 3. 영은님 측정과 연결
| 물음 | 정리 |
|---|---|
| 지금 걸려 있는지 확인하는 방법 | Prometheus에서 `resilience4j_bulkhead_max_allowed_concurrent_calls{application="gateway"}`를 조회한다. 값이 25로 나오면 걸려 있다. gateway가 이 지표를 실제로 내는지는 확인 필요 |
| 끄는 방법 | `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false`(gateway 전체의 기본 벌크헤드를 끈다) |
| 값을 바꾸는 방법 | `resilience4j.bulkhead.instances.auth-redis.max-concurrent-calls`(그리고 `max-wait-duration`). `resilience4j-spring-boot3`의 레지스트리가 이 설정으로 인스턴스를 먼저 만들면 provider가 그것을 쓴다고 보이지만 확인 필요 |
| 측정 조건 | gateway 공통 설정 변경이다. 측정 전용 덮어쓰기 파일로 `measure-mode.sh on`에 넘기고 `off`로 되돌리는 방식이 기록이 남는다. 측정 기록표에 키·값과 배포 커밋을 적는다 |
| 비교 | 같은 커밋·시나리오에서 기본값(25)과 바꾼 값을 연달아 재야 "503이 이 벌크헤드 때문인가"에 답할 수 있다 |

### 4. config 서버를 건드리지 않고 바꿀 수 있나 (영은님 후속 질문)
- 서버의 `config-repo`를 직접 고치면 안 된다. config-server는 서버 저장소의 `./config-repo`를 읽고(`compose.yml` 36-37행), 그 폴더는 배포 커밋으로 checkout된 상태다. 손으로 고치면 `.deploy/current`(배포 커밋)와 실제 설정이 어긋나 측정 기록표의 커밋이 조건을 설명하지 못한다(`deploy-server-access.md` 금지 항목).
- config-repo를 고칠 필요가 없다. config 클라이언트 5.0.6의 `ConfigServerConfigDataLoader`는 서버 값을 일반 config data로 넣는다(바이트코드 확인, 서버 쪽 `configserver:overrides`만 예외). Spring Boot 외부 설정 순서에서 config data는 OS 환경변수·`SPRING_APPLICATION_JSON`보다 낮다. 그래서 gateway 컨테이너 환경변수로 덮으면 이긴다. 팀이 이미 쓰는 방식(`compose.queue-hold.yml`의 `QUEUE_ADMISSIONENABLED`)과 같다. 배포 서버에서 실제로 먹는지는 확인 필요다.
- 키 이름:
  | 하려는 것 | 환경변수 |
  |---|---|
  | 기본 벌크헤드 끄기 | `SPRING_CLOUD_CIRCUITBREAKER_BULKHEAD_RESILIENCE4J_ENABLED: "false"` |
  | 값 바꾸기 | `SPRING_APPLICATION_JSON: '{"resilience4j":{"bulkhead":{"instances":{"auth-redis":{"max-concurrent-calls":200}}}}}'` (200은 예시) |
- 끄기는 안전하게 동작한다(바이트코드 확인). 끄면 벌크헤드 provider 빈이 만들어지지 않는데, 서킷 브레이커 팩토리는 이 빈을 `@Autowired(required = false)`로 받는다. 그래서 앱은 그대로 뜨고, `run()`은 provider가 null이면 벌크헤드 없이 서킷 브레이커만 씌운다.
- 환경변수는 실행 중인 컨테이너에 넣을 수 없고, 벌크헤드도 앱이 뜰 때 만들어진다. 그래서 gateway 컨테이너를 다시 만들어야 한다(`measure-mode.sh on <파일>`이 이 일을 한다). 다시 뜨는 동안 요청이 실패하므로 측정 회차 사이에 한다.
- 값 바꾸기에 일반 환경변수를 쓰지 않는 이유: 환경변수 이름에는 `-`를 넣을 수 없어 맵 키 `auth-redis`가 `authredis`로 묶인다. 그래서 JSON으로 넘긴다.
- 넣는 방법: 위 값을 `services.gateway.environment`에 둔 덮어쓰기 파일을 만들어 `MEASURER=<이름> script/loadtest/measure-mode.sh on <파일>`로 얹는다. `off`가 그 파일 없이 다시 띄우므로 자동으로 되돌아간다. 파일은 저장소에 커밋해 서버로 가져오는 것이 원칙이다. 측정 중 main 머지를 멈춘 상태라 서버 밖 경로에 두고 쓰면 기록표에 파일 내용을 그대로 적는다.

### 4-1. "컨테이너를 다시 만든다"는 무슨 뜻인가 (강민 후속 질문)
- 컨테이너의 환경변수는 컨테이너를 만들 때 정해지고, 실행 중에는 바꿀 수 없다. `docker restart`는 같은 컨테이너를 같은 환경변수로 다시 켤 뿐이라 새 값이 들어가지 않는다.
- 그래서 같은 이미지로 컨테이너를 새로 만든다(기존 것을 멈추고 지운 뒤 새 환경변수로 생성). `docker compose up`은 compose 설정이 바뀐 서비스만 이렇게 다시 만들고 나머지는 그대로 둔다. `measure-mode.sh on <파일>`이 안에서 하는 일이 이것이다.
  | 방법 | 새 환경변수 반영 | 비고 |
  |---|---|---|
  | `docker restart gateway` | 안 됨 | 같은 컨테이너를 다시 켤 뿐이다 |
  | 설정 새로고침(`/actuator/refresh` 등) | 안 됨 | 환경변수가 아니고, 벌크헤드는 이미 만들어진 객체다. gateway에 refresh 엔드포인트도 열려 있지 않다 |
  | compose 파일에 환경변수를 넣고 `up` | 됨 | 이미지는 같고 컨테이너만 바뀐다. 측정 날에는 `measure-mode.sh on <파일>`로 한다 |
- 대가: 새 gateway가 뜰 때까지(수십 초 안팎, 확인 필요) 8080 요청이 모두 실패한다. 다른 서비스와 DB·Redis는 그대로다. 그래서 측정 회차 사이에만 한다.

### 4-2. 설정을 바꿔 main에 머지하고 다시 재면 되지 않나 (강민 후속 질문)
- 된다. 정식 배포 경로라 규칙 예외가 없고, 배포 커밋 하나로 조건이 설명된다. 다만 `deploy.sh`가 하는 일 때문에 실험용으로는 비싸다(`script/deploy/deploy.sh` 48-58행 확인).
  - `.env`와 `compose.deploy.yml`만으로 다시 띄운다. 측정 모드가 풀린다(측정 키·샘플링 0.01·9090). `.env.measure`와 `.deploy/measure-mode`는 지우지 않아 `status`는 켜진 것처럼 보일 수 있다. 같은 측정자가 `on`을 다시 하면 남은 `.env.measure`를 그대로 써서 k6 키는 유지된다.
  - 이미지 태그가 바뀌므로 앱 컨테이너가 모두 새로 만들어진다. Mock PG 메모리의 카드가 사라져 시드를 다시 넣어야 한다(handoff 0-2절 메모).
  - 되돌릴 때 PR과 배포를 한 번 더 해야 하고, 그동안 팀 전체의 기본값이 바뀐다(강민의 11/10 측정 포함). Gateway 공통 설정이라 contract 대상이다.
- 비교:
  | 안 | 배포 횟수 | 되돌리기 | 맞는 때 |
  |---|---|---|---|
  | `gateway.yml` 값을 바꿔 머지 | 바꿀 때 1번 + 되돌릴 때 1번 | PR·배포 | 측정으로 새 기본값을 정한 뒤 확정할 때 |
  | 측정 전용 덮어쓰기 파일을 머지해 두고 `measure-mode.sh on <파일>`로 켜고 끔 | 파일을 올릴 때 1번 | `on`(파일 없이) 또는 `off`, gateway만 다시 만듦 | 기본값과 바꾼 값을 여러 번 오가며 비교할 때 |

### 4-3. 이미지 태그, 시드, 팀 기본값 (강민 후속 질문)
- **이미지 태그**: 배포 이미지 이름 끝에 붙는 버전표다. 이 프로젝트는 40자 커밋 SHA를 쓴다(`compose.deploy.yml`의 `ghcr.io/iseonjwa/gateway:${IMAGE_TAG}`). main에 머지하면 Jenkins가 새 SHA로 9개 이미지(config-server, eureka, gateway, user·reservation·payment·assistant-service, mock-pg, frontend)를 모두 만들고(`docker-bake.hcl` 55행), `deploy.sh`가 그 SHA로 띄운다. 코드가 같아도 태그가 바뀌니 Compose는 9개 앱 컨테이너를 모두 새로 만든다. 이미지 버전이 고정된 Postgres·Redis·Kafka는 그대로다.
- **시드는 저절로 돌아오지 않는다**:
  | 데이터 | 저장 위치 | 재배포 뒤 |
  |---|---|---|
  | 회원, 회차, 추첨, 응모, 카드 등록 기록 | Postgres(볼륨) | 남는다 |
  | Mock PG가 발급한 빌링키 | Mock PG 메모리(`BillingKeyStore`, "다시 띄우면 비워진다") | 사라진다 |
  - DB에는 빌링키가 남고 Mock PG에는 없어져, 이후 자동결제가 `BILLING_KEY_MISSING`으로 실패한다(handoff F2와 같은 현상).
  - `deploy.sh`와 Jenkins에는 시드 단계가 없다. `seed-lottery-entrants.sh`는 카드 등록을 마친 계정을 상태 폴더에 적어 두고 다시 등록하지 않으므로, 그냥 다시 돌려도 빌링키가 복구되지 않는다. k6 L2가 실행마다 카드를 새로 등록하는지는 확인 필요.
- **팀 전체의 기본값**: `config-repo/gateway.yml`은 main 한 벌을 모두가 같이 쓴다. 여기를 바꿔 머지하면 그 뒤의 배포 서버, 다른 사람의 측정, 시연, 각자 로컬 실행이 모두 바뀐 값으로 돈다. 측정 전용 덮어쓰기 파일은 `measure-mode.sh on <파일>`로 얹은 동안에만 효과가 있다.

### 5. 느린 응답 기준 시간도 바꾸려면 (영은님 후속 질문)
- `auth-redis`에는 손잡이가 셋 있고 모두 같은 503을 낸다. 응답만 보고는 어느 것이 원인인지 가릴 수 없다.
  | 손잡이 | 위치 | 지금 값 | 바꾸는 방법 |
  |---|---|---|---|
  | Redis 조회 시한 | `GatewayAuthenticationFilter.REDIS_TIMEOUT` (코드 상수) | 100ms | 코드 변경만 가능. 설정·환경변수로는 못 바꾼다 |
  | 벌크헤드 | 라이브러리 기본값 | 동시 25, 대기 0 | 4절 |
  | 서킷 브레이커 느린 호출 기준 | `config-repo/gateway.yml` `slow-call-duration-threshold` | 50ms, 느린 호출 50% 이상(20건 기준)이면 10초 OPEN | `SPRING_APPLICATION_JSON`으로 `resilience4j.circuitbreaker.instances.auth-redis.slow-call-duration-threshold`를 덮는다. 이것도 키에 `-`가 있어 일반 환경변수로는 맞지 않는다 |
- 원인을 가리려면 한 번에 하나만 바꾼다. 둘을 같이 바꾸면 503이 줄어도 어느 쪽 덕인지 말할 수 없다.
- 원인을 가리는 지표(gateway가 내는지는 확인 필요): `resilience4j_circuitbreaker_state{name="auth-redis"}`(OPEN 여부), `resilience4j_circuitbreaker_slow_calls{name="auth-redis"}`, `resilience4j_bulkhead_available_concurrent_calls{name="auth-redis"}`(0에 닿는지).

### 6. 배운 점
- 스타터 의존성은 설정 파일에 없는 동작을 기본값으로 켠다. "설정에 없으니 없다"는 검색으로 확인한 것이 아니다. 자동 설정 조건(`@ConditionalOnProperty(matchIfMissing = true)`)까지 봐야 한다.
- 반대로 "지표가 보이니 있다"도 어느 서비스·이름의 지표인지 라벨을 봐야 한다.

## 출처
- 라이브러리 바이트코드: `spring-cloud-circuitbreaker-resilience4j-5.0.3.jar`(`ReactiveResilience4JCircuitBreaker`, `ReactiveResilience4jBulkheadProvider`, `Resilience4jBulkheadConfigurationBuilder`, 설정 메타데이터), `resilience4j-bulkhead-2.3.0.jar`(`BulkheadConfig` 기본값)
- 코드: `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`, `config-repo/gateway.yml`
- 대시보드: `infra/grafana/dashboards/payment.json`, `lottery.json`
