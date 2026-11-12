---
title: CPU를 늘리지 않고 Gateway가 부하를 감당하게 하는 기술은 무엇인가
date: 2026-11-11
updated: 2026-11-11
asker: KWON-WOO
level: 고급
tags: [gateway, load-shedding, near-cache, jwt, etag, concurrency-limit, tcp-buffer, admission-control]
---

# CPU를 늘리지 않고 Gateway가 부하를 감당하게 하는 기술은 무엇인가

## 질문
CPU를 늘리는 방법 말고, 게이트웨이가 부하를 감당할 수 있게 하는 기술은 무엇이 있을까? 물리적인 방법 말고 다른 기술을 도입해 개선할 방법을 확인해 줘.

## 정정 (차단기 값 비교 측정 뒤)
운영 차단기 값과 강민·영은의 시험 값(재측정 2회 값)을 local에서 비교했다(측정 브랜치 `reservation-service/k6/STEPS.md` "Gateway 차단기 시험 값 비교 실측").
- 아래 1번의 "Redis 조회가 늦어져 차단기가 열렸다"는 확인되지 않은 추정이었고, 운영 값으로 재니 다른 원인이었다. 운영 값에서 차단기는 Redis 오류 0건, 느린 호출 비율 0%인데 실패율 50%로 열렸다. 동시 확인 25개를 넘은 순간의 **벌크헤드 거절이 차단기 실패로 세어진 것**이다. 시험 값의 `BulkheadFullException` 무시가 이 연쇄를 끊는다(차단기 열림 0회). 1번이 가리킨 640명 측정은 측정 전용 완화 값(실패율 100%)이었고 원인 로그가 없다. 완화 값에서도 벌크헤드 25는 그대로라 벌크헤드 거절 쪽일 가능성이 크다.
- 시험 값에서는 통과하는 830KB 응답이 늘어, 상한 없는 환경에서도 Gateway가 OOMKilled로 죽었다(1회). 그래서 3번(메모리)과 "감당할 수 있는 만큼만 들인다"의 동시 처리 상한은 시험 값과 함께 필요하다. 인증 벌크헤드는 Redis 확인 동안만 동시 수를 막고, 백엔드 응답을 받는 구간은 막지 못한다.

## 짧은 답
오늘 측정에서 Gateway는 세 가지 방식으로 한계에 닿았다.
1. **인증 차단기 열림:** 로그아웃 확인용 Redis 조회가 CPU 부족으로 늦어져 `auth-redis` 차단기가 열렸다. 매진을 피한 스트레스 640명에서 즉시 503이 난 원인이다.
2. **요청당 CPU:** JWT 검증, Redis 조회, 830KB 중계, 요청 로그가 요청마다 든다.
3. **메모리:** 몰린 큰 응답이 커널 소켓 받기 버퍼와 직접 버퍼에 쌓여 상한에 닿았다.

그래서 개선은 네 갈래다.
- **Gateway가 요청마다 하는 일을 줄인다:** 로그아웃 확인을 Gateway 메모리에 두고 Redis로 바뀐 것만 알리기, JWT 검증 결과 캐시, 요청 로그 표본화.
- **Gateway를 지나는 바이트를 줄인다:** ETag·304, 미리 압축한 공유 응답.
- **감당할 수 있는 만큼만 들인다:** 대기열 입장 속도를 측정 한계에 맞추기, 적응형 동시 처리 상한, 경로별 격리.
- **메모리 예산을 바로 짠다:** Gateway→서비스 연결의 받기 버퍼 상한, 힙을 줄여 버퍼 몫 확보.

## 이 판단의 근거 (확인한 사실)
- 인증 필터(`GatewayAuthenticationFilter`)는 로그인이 필요한 요청마다 JWT 서명을 검증한다. 그다음 Redis `hasKey blacklist:<jti>`로 로그아웃 여부를 묻는다. 이 조회는 `auth-redis` 차단기 안에서 100ms 시한으로 돈다.
- 입장 토큰 필터(`EntryTokenFilter`)는 Redis 없이 서명만 확인한다.
- `RequestLogWebFilter`는 요청마다 INFO 로그를 한 줄 남긴다. `iseonjwa.request-log.enabled=false`로 끌 수 있다.
- `config-repo/gateway.yml`에는 속도 제한, 동시 처리 상한, 압축, 연결 버퍼 설정이 없다. HTTP 클라이언트는 연결 시한 1초, 응답 시한 3초뿐이다.
- 측정에서 확인한 것:
  - 매진을 피한 스트레스(상한 없음): 320명까지 100%, 640명부터 Gateway 즉시 503. reservation-service는 오류 0건이었다.
  - CPU 상한(4코어): 160명에서 p95 149ms, 320명에서 실패 시작.
  - Gateway 메모리: 1,280명에서 커널 소켓 받기 버퍼 최대 약 590MiB, 직접 버퍼 최대 183MB. 힙 사용은 Prometheus 기준 최대 225MB였다.

## 자세히

### 1. Gateway가 요청마다 하는 일 줄이기 (CPU)

**로그아웃 확인을 Gateway 메모리에 두고, 바뀐 것만 Redis로 알리기 (near-cache + pub/sub)**
- 지금은 요청마다 Redis에 묻는다. 로그아웃은 드문 일이므로, 로그아웃된 토큰 목록(만료 전인 것만)을 Gateway 메모리에 둔다. 로그아웃이 생기면 Redis pub/sub으로 모든 Gateway에 알린다.
- 효과: 요청마다 하는 Redis 왕복과 차단기 판정이 사라진다. CPU가 모자라 Redis 응답 처리가 늦어지고 차단기가 열리는 실패 방식이 없어진다. 매진을 피한 스트레스에서 640명을 막은 것이 이 차단기였다.
- 대가: 알림이 늦거나 유실되면 로그아웃된 토큰이 잠깐 통한다. 그래서 기동할 때와 주기적으로 Redis에서 목록을 다시 읽어 맞춘다. 알림을 받지 못한 동안은 지금처럼 Redis에 묻는 방식으로 돌아간다.
- 더 단순한 방법: "로그아웃 아님" 결과를 1~5초 캐시한다. 로그아웃이 그 시간만큼 늦게 반영되는 보안 대가를 팀이 받아들일 수 있을 때만 쓴다.

**JWT 검증 결과 캐시**
- 같은 사용자가 조회 → 선점 → 재조회로 여러 번 부르므로 같은 토큰이 반복된다. 토큰 문자열(또는 해시)을 키로 검증 결과를 만료 시각까지 메모리에 둔다(크기 상한 필요). 서명 검증과 JSON 해석을 건너뛴다.
- 효과는 요청당 CPU 중 JWT 검증이 차지하는 비중만큼이다. 이 비중은 재지 않았다. 넣기 전에 JFR로 Gateway CPU를 나눠 보는 것이 순서다.

**요청 로그 표본화·비동기화**
- 1,280명 단계에서 Gateway는 10초에 로그 약 1.2만 줄을 남겼다. 운영에서는 오류·느린 요청만 남기거나, 성공 요청은 표본만 남긴다. 출력은 비동기(AsyncAppender)로 바꾼다. 요청 추적은 traceId로 이미 할 수 있다.

### 2. Gateway를 지나는 바이트 줄이기 (CPU + 메모리)

**ETag와 304**
- 좌석 상태는 1초마다 한 번 바뀐다(P4c). 응답에 그 스냅숏의 버전을 `ETag`로 붙인다. 클라이언트가 `If-None-Match`로 다시 물을 때 같으면 본문 없이 304로 답한다. 409 뒤 다시 조회하는 요청은 같은 1초 안에 올 때가 많아 304가 많이 나올 것이다.
- 효과: 830KB 중계가 수백 바이트가 된다. Gateway의 직접 버퍼와 소켓 버퍼에 쌓일 바이트가 그만큼 준다.
- 브라우저는 `ETag`와 `Cache-Control: no-cache`가 있으면 조건부 요청을 알아서 보낸다. 서버 쪽은 좌석 조회 담당 영역이다.

**미리 압축한 공유 응답**
- 1초 스냅숏마다 gzip 압축본을 한 번 만들어 둔다(P4d + gzip). Gateway는 압축된 바이트를 그대로 중계하므로 지나는 바이트가 압축률만큼 준다. Gateway에서 요청마다 압축하면 Gateway CPU를 더 쓰므로 하지 않는다.
- 배치·상태 분리와 비트맵(830KB → 약 2.5KB)은 효과가 가장 크지만 계약이 바뀐다([극복 방안 노트](2026-11-11-overcome-large-seat-response.md)).

### 3. 감당할 수 있는 만큼만 들이기 (부하 제어)

**대기열 입장 속도를 측정 한계에 맞추기**
- 이 시스템은 대기열이 초당 몇 명을 들일지 정한다(`queue.admit-per-second`, 지금 50). SA 3-3은 이 값을 측정한 한계로 정한다고 했다. 이 단계의 시스템에 가장 맞는 부하 제어 장치다.
- 측정 근거: local 4코어 상한에서 160명은 버티고 320명부터 실패했다. 배포 측정으로 한계를 확인한 뒤 그보다 낮게 둔다. Gateway가 무너지기 전에 들어오는 양 자체를 제한한다.
- 대기열 담당 영역이다.

**적응형 동시 처리 상한 (adaptive concurrency limit)**
- Gateway가 지금 처리 중인 요청 수를 세고, 응답 시간이 늘기 시작하면 상한을 낮춘다(Netflix `concurrency-limits`의 gradient 방식, Envoy adaptive concurrency와 같은 원리). 상한을 넘는 요청은 서비스로 보내기 전에 즉시 503 + `Retry-After`로 돌려보낸다.
- 효과: 큰 응답이 동시에 수백 개 몰리는 상황 자체를 막는다. 메모리 급등과 스왑, 강제 종료의 출발점을 끊는다. 고정 상한보다 기기 성능이 달라도 맞춰진다.
- 대가: 거절이 늘 수 있다. 상한이 너무 빨리 줄지 않게 측정으로 값을 맞춘다.

**경로별 격리와 사용자별 속도 제한**
- 좌석 조회 경로에만 동시 처리 상한을 두면 로그인·결제 경로는 좌석 조회 폭주와 상관없이 처리된다. 서비스 사이 격리(Z 측정)를 Gateway 안에서도 하는 것이다.
- 사용자별 속도 제한은 Gateway 메모리에서 세는 방식(Bucket4j 등)이 싸다. Spring Cloud Gateway의 `RequestRateLimiter`는 Redis를 요청마다 부른다. 그래서 지금 문제인 Redis 의존을 하나 더 늘린다.

### 4. 메모리 예산 바로 짜기 (강제 종료 방지)

**Gateway→서비스 연결의 받기 버퍼 상한**
- 쌓인 곳은 reservation-service에서 받은 뒤 Gateway가 아직 읽지 않은 커널 받기 버퍼였다. 연결마다 받기 버퍼 상한을 두면(Reactor Netty `HttpClient`의 `SO_RCVBUF`, 또는 컨테이너 sysctl `net.ipv4.tcp_rmem`) 합계에 위가 생긴다. 버퍼가 차면 TCP 흐름 제어로 서비스 쪽이 보내기를 멈춘다.
- 대가: 연결 하나의 처리량이 버퍼 크기에 묶인다. 같은 VPC처럼 왕복이 짧은 곳에서는 영향이 작을 것으로 보지만 재야 한다. 설정 방법(Spring Cloud Gateway의 `HttpClientCustomizer`, 컨테이너 sysctl)이 이 버전에서 그대로 되는지는 확인이 필요하다.

**힙을 줄여 버퍼 몫 확보, 스왑 정책 명시**
- 측정에서 Gateway 힙 사용은 최대 225MB였는데, 힙 상한은 512MB(평소 커밋 248MB)다. 힙 상한을 실제 사용에 맞춰 줄이면 같은 768MiB 안에서 소켓·직접 버퍼에 줄 몫이 커진다. GC가 잦아지는지는 함께 본다.
- 스왑을 허용하면 죽지 않는 대신 수 초씩 멈췄다(GC 6.9초). 차라리 빨리 실패할지(스왑 끔), 느려질지 정책을 정해 compose에 적는다(`memswap_limit`).

### 5. 실행 방식 바꾸기 (범위 큼)
- **Nginx·Envoy 엣지:** C/C++ 기반이라 요청당 CPU·메모리가 작고 예측 가능하다. 인증 로직을 `auth_request`나 `ext_authz`로 옮겨야 한다.
- **GraalVM 네이티브 이미지 / AOT:** 평소 메모리가 작아지고 JIT 예열이 없어진다. 재기동 직후 JIT가 몰려 차단기가 열린 문제(ISE-230 댓글)에도 닿는다. 다만 Spring Cloud Gateway와 쓰는 라이브러리의 네이티브 지원 범위는 확인하지 않았다.

### 비교와 추천 순서
| 순서 | 기술 | 막는 실패 | 비용 | 담당 |
|---|---|---|---|---|
| 1 | 대기열 입장 속도를 측정 한계에 맞추기 | 들어오는 양 자체 | 설정 한 줄(배포 측정 후 값 결정) | 대기열 |
| 2 | 로그아웃 확인 near-cache + pub/sub | 인증 차단기 열림(640명 503), 요청당 Redis | 중간. 보안 일관성 설계 필요 | Gateway |
| 3 | ETag·304, 미리 압축한 공유 응답 | 요청당 CPU, 메모리 급등 | 중간. 외부 계약 변경 거의 없음 | 좌석 조회 |
| 4 | 적응형 동시 처리 상한 + 받기 버퍼 상한 + 힙 축소 | 메모리 급등, 스왑, 강제 종료 | 중간. 값은 측정으로 | Gateway |
| 5 | 요청 로그 표본화·비동기화, JWT 검증 캐시 | 요청당 CPU | 작음. 효과는 JFR로 먼저 확인 | Gateway(공통 로그 계약은 ISE-182) |

1번은 다른 방법과 함께 써야 한다. 2~4번은 그 한계 자체를 올린다.

## 이 프로젝트에서는
- 인증: `gateway/src/main/java/com/iseonjwa/gateway/security/GatewayAuthenticationFilter.java`(`isRevoked`), 설정 `config-repo/gateway.yml`(`resilience4j.circuitbreaker.instances.auth-redis`)
- 요청 로그: `gateway/src/main/java/com/iseonjwa/gateway/observability/RequestLogWebFilter.java`
- 입장 속도: `config-repo/reservation-service.yml`의 `queue.admit-per-second`
- 측정 근거: 측정 브랜치 `reservation-service/k6/STEPS.md` "측정 회고 뒤 세 측정 실측", "Gateway OOMKilled 재현과 원인 확인 실측"
- Gateway는 담당이 따로 없어 강민이 맡아 왔다(ISE-230). 2·4·5번은 근거와 함께 `contract` 이슈로 제안한다.

## 면접 포인트
- "CPU를 늘리지 않고 처리량을 올리려면?" → 먼저 요청 하나가 쓰는 일을 나눠 본다. 외부 호출(Redis), 검증(JWT), 중계 바이트, 로그 중 줄일 수 있는 것부터 줄인다. 요청마다 원격 저장소를 부르는 대신, 드물게 바뀌는 데이터는 근처 캐시와 변경 알림으로 바꾼다.
- "과부하 보호는 어디서 해야 하나?" → 가장 앞에서, 가장 싸게 거절한다. 이 시스템에서는 대기열 입장 속도가 첫 문이다. 그다음 Gateway가 처리 중인 요청 수를 보고 서비스로 보내기 전에 거절한다. 늦게 거절할수록 이미 쓴 CPU·메모리가 아깝고, 강제 종료처럼 더 나쁜 실패가 생긴다.
- "컨테이너 메모리를 어떻게 나누나?" → 힙만이 아니라 메타스페이스·코드·스레드·직접 버퍼·커널 소켓 버퍼까지 합쳐 상한 안에 들게 한다. 커널 소켓 버퍼는 JVM이 보지 못하므로 cgroup `memory.stat`의 `sock`으로 본다.

## 참고
- 확인한 코드·설정: origin/main의 `GatewayAuthenticationFilter`, `EntryTokenFilter`, `RequestLogWebFilter`, `config-repo/gateway.yml`, 루트 `build.gradle`(Spring Boot 4.1.2, Spring Cloud 2025.1.4)
- 확인하지 않은 것: Gateway CPU에서 JWT 검증·로그가 차지하는 비중, `HttpClientCustomizer`와 컨테이너 sysctl의 이 버전 적용 방법, Spring Cloud Gateway 네이티브 이미지 지원 범위
- Netflix concurrency-limits(gradient 방식), Envoy adaptive concurrency filter, Redis pub/sub, HTTP `ETag`·`If-None-Match`
