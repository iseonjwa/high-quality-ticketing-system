# 한 서버 안의 MSA: 코드 경계를 자원 경계로 옮기는 방법 (설계 메모)

상태: 초안(2026-11-11, kangmin 문제 제기, 에이전트 정리). 처음 정리할 때는 코드·compose 변경, 서버 접속, 측정을 하지 않았다. 숫자는 아래 원천 문서·설정 파일에서 옮겼고, 옮기지 못한 것은 "확인 못 함", 직접 셈한 것은 "계산값"으로 적었다. 그 뒤 진행은 바로 아래 표에 적는다.

## 진행 상태

| 방법 | 상태 | 내용 |
|---|---|---|
| M1 Gateway 메모리 예산 | **dev 반영(A안), 배포 전** | `compose.deploy.yml` gateway `mem_limit` 768m → 1024m, `-XX:MaxDirectMemorySize=128m`, `-Xmx512m` 유지. 측정 덮어쓰기로 5차 실패 조건(활성 VU 5,849)을 다시 돌려 OOM 0, 메모리 최대 785MiB였다([open-peak-scenarios.md](open-peak-scenarios.md) "배포 서버 5차" M1) |
| A1 Gateway 맨 앞 동시 처리 수 상한 | **dev 반영, 배포 전·측정 전** | `InFlightLimitFilter`(`GlobalFilter` -200). 상한 `iseonjwa.gateway.in-flight.max-requests` 기본 **64**, 거절은 503 + `Retry-After: 2` + `GATEWAY_OVERLOADED`, 운영자 경로(`/api/v1/admin/**`) 제외. 지표 `gateway_in_flight_requests`·`gateway_in_flight_limit`·`gateway_in_flight_rejected_total`. 첫 값은 2-2절의 500이 아니라 5차 "A1 첫 상한값 근거"의 64다(아래 2-2절 주석) |
| C1·C2 관측 축소 | **보류**(kangmin 판단: 위험) | 0b JFR에서 관측 묶음이 Gateway CPU의 약 24%였지만, 장애 분석·시연 추적이 약해질 위험이 커 손대지 않는다. 동시 수 상한을 넣고도 거절 비용 때문에 CPU가 내려오지 않으면 다시 본다 |

## 0. 문제와 전제

> 지금 구조는 MSA인 듯 아닌 MSA다. 패키지 구조나 코드는 나뉘어 있지만 인스턴스는 하나로 같이 띄워져 자원을 공유한다. 이 상황 안에서 할 수 있는 방법을 찾아야 한다.

바꾸지 않는 전제:

| 전제 | 근거 |
|---|---|
| 4 vCPU EC2 한 대에 앱 9개(프런트 포함), PostgreSQL 3개, Redis, Kafka, 관측 도구를 docker compose로 띄운다 | [ADR 0047](../../decisions/0047-single-ec2-first-managed-split-review.md), `compose.deploy.yml` |
| M4(11/16) 안에 서버 추가·매니지드 분리를 하지 않는다 | 작업 범위 |
| Eureka·Config Server(Spring Cloud MSA 구성)는 유지한다 | 발제 요건 |

지금 서비스 사이에 **있는** 경계와 **없는** 경계:

| 경계 | 지금 | 근거 |
|---|---|---|
| 코드·배포 단위 | 있다. 서비스마다 이미지·컨테이너가 따로다 | `compose.deploy.yml` |
| 데이터 | 있다. PostgreSQL이 서비스마다 하나다(user·reservation·payment) | 같은 파일 |
| 메모리 | 있다. 컨테이너마다 `mem_limit`. 상한 합 12,672MiB / 장비 약 15.7GiB(계산값) | 같은 파일, [가벼운 컨테이너 노트](../../learning/kangmin/qna/2026-11-10-lighten-containers-on-shared-server.md) 1절 |
| DB 연결 | 있다. Hikari 풀 reservation 5, user 5, payment 20 | `config-repo/*-service.yml` |
| 외부 호출 동시 수 | 일부 있다. Gateway `auth-redis` 벌크헤드 50, 결제 `pg-approve` 10, 추첨 자동결제 벌크헤드(ADR 0051) | ADR 0050, 0051 |
| **CPU** | **없다.** `cpus`·`cpu_shares`·`cpuset`이 어디에도 없다. 21개 컨테이너가 400%를 먼저 잡는 쪽이 쓴다 | `compose.yml`, `compose.deploy.yml` grep |
| **맨 앞 유입량** | **없다.** Gateway에 동시 처리 수·요청률 상한이 없다 | 5차 0a 기록 |
| **JVM 밖 메모리** | **없다.** `MaxDirectMemorySize` 미지정, 힙+비힙+JVM 밖 합이 컨테이너 상한에 맞춰져 있지 않다 | 같은 기록 |

즉 "MSA인 듯 아닌" 부분은 정확히는 **CPU와 유입량에 경계가 없는 것**이다. 메모리·데이터·연결은 이미 서비스 단위로 나뉘어 있다. 이 문서의 방법들은 비어 있는 두 칸을 채우고, 메모리 칸을 실제 쓰임에 맞추는 일이다.

## 1. 근거 측정값

모두 배포 서버 5차 0a(2026-11-11 UTC 08:17\~08:36, G1 인증 경로, ADR 0050 값)다. 정본은 [open-peak-scenarios.md](open-peak-scenarios.md) "배포 서버 5차", 요약은 [gateway-auth-redis-resilience.md](gateway-auth-redis-resilience.md) 5-2절이다.

| 목표 | 굿풋(200ms 안 성공) | 성공 p95 | 서버 CPU 평균 | gateway | reservation | alloy | kafka | redis |
|---|---|---|---|---|---|---|---|---|
| 1,000/s | 999 | 15ms | 75% | 116% | 52% | 23% | 12% | 12% |
| 1,500/s | 1,440 | 152ms | 93% | 160% | 72% | 25% | 31% | 15% |
| 2,000/s | 1,469 | 435ms | 99% | 188% | 78% | 27% | 19% | 14% |

- Gateway 안: `reactor-http-epoll` 4개 합 130%, `lettuce-epollEventLoop` 39%(2,000/s). Gateway CPU의 약 70%가 HTTP 이벤트 루프, 약 20%가 Redis 응답 처리다.
- 한 건당 CPU: 1,500/s에서 서버 2.5ms, gateway 1.07ms, reservation 0.48ms, alloy 0.17ms(원천의 계산값).
- Gateway OOM 3회: 컨테이너 상한 768MiB, `-Xmx512m`, 비힙 약 158MB, JVM 지표에 안 잡힌 메모리 약 130MB(원천의 계산값). 활성 VU 5,300\~5,900에서 웜업 중 사망, 2,931에서는 웜업 생존(메모리 최대 488MiB), 3,000으로 3,000/s 본 측정 중 사망.
- Kafka는 부하 없는 쉬는 구간에도 최대 140%까지 튀었다. 11/11 S1 측정에서도 "Kafka가 12초마다 90\~160% 튀었다"는 기록이 있다. 원인은 확인 못 함.

이 문서에서 새로 셈한 값:

| 값 | 계산 | 결과(계산값) |
|---|---|---|
| 컨테이너 밖 CPU | 서버 CPU × 400% − (gateway CPU ÷ gateway 몫) | 1,000/s: 300 − 232 = 약 68%, 1,500/s: 372 − 320 = 약 52%, 2,000/s: 396 − 342 = 약 54% |
| 뜻 | 400% 중 약 13\~17%를 `docker stats`에 안 보이는 쪽(dockerd·containerd-shim, 커널 네트워크 처리, 측정 수집기 자신)이 쓴다(추정) | `docker stats` 3초 평균과 `vmstat` 1초 평균을 섞은 값이라 거칠다 |
| alloy의 부하 비례성 | 요청 2.0배(1,000 → 2,000/s)에 alloy CPU 1.17배(23 → 27%) | 요청 로그 줄 수보다 고정 비용(컨테이너 탐색 5초 주기 등)이 큰 것으로 보인다(추정, 확인할 질문) |

## 2. 방법별 정리

칸 설명: **지키는 것**은 이 방법이 막는 실패, **효과**는 측정값에서 셈한 기대치(측정 아님), **담당**은 바꾸는 파일의 주인, **확인**은 같은 조건 재측정에서 볼 지표다.

### 2-1. 자원 격리

| 방법 | 지키는 것 | 근거·효과(계산값) | 비용·위험 | 담당 | 확인 |
|---|---|---|---|---|---|
| **M1. Gateway 메모리 예산 맞추기** (A안: `mem_limit` 768m → 1024m, `-Xmx512m` 유지 / B안: 상한 768m 유지, `-Xmx384m`) + 공통으로 `-XX:MaxDirectMemorySize=128m` | OOM으로 Gateway가 통째로 내려가는 것(3회 측정) | A안: 1,024MiB(약 1,074MB) − 800MB = 여유 약 270MB. 상한 합 12,672 → 12,928MiB, 장비 안에 든다. B안: 384 + 158 + 130 = 672MB, 768MiB(약 805MB) 대비 여유 약 130MB. 직접 메모리 상한은 "조용한 강제 종료"를 "요청 단위 `OutOfDirectMemoryError`"로 바꾼다(일반론, 이 장비에서 확인 못 함) | A안은 다른 컨테이너 몫 256MiB를 가져간다. B안은 힙이 작아 GC가 잦아져 CPU가 늘 수 있다(확인할 질문). **상한만 맞추고 유입을 안 묶으면 동시 수가 더 늘 때 같은 일이 난다.** JVM 밖 130MB의 정체(Netty 풀 직접 메모리 추정)를 모른다 | 인프라(`compose.deploy.yml`) | 5차 실패 조건(`PRE_VUS` 3,000·`MAX_VUS` 6,000, 웜업 3,000/s)을 그대로 다시 돌려 `RestartCount` 불변, `dmesg` OOM 0건, `jvm_memory_committed_bytes` 최대 |
| M2. CPU 가중치 `cpu_shares` | 피크 때 관측·관리 도구가 핵심 경로 CPU를 빼앗는 것 | 기본값 1,024. 예: 상태 저장(postgres 3, redis, kafka) 1,536, 앱 1,024, 관측·관리(alloy, loki, zipkin, grafana, kafka-ui, assistant) 256. Prometheus는 측정 도구라 기본값 유지. 경합 때만 작동하고 남는 CPU는 빌려 쓴다. alloy 약 25%가 전부 비켜 준다면 250ms/s ÷ 한 건당 2.0\~2.5ms = 초당 100\~125건 여유(상한, 계산값) | 총량은 그대로다. 낮춘 쪽(로그 수집)이 밀려 로그·로그 기반 알림이 늦게 도착한다. JDK 19 이상은 `cpu_shares`로 JVM 스레드 수를 정하지 않는다(일반론) → 스레드 수 변화 없음 | 인프라 | 같은 0a 조건 재측정: 단계별 굿풋·p95, 컨테이너별 CPU, Loki 수집 지연(로그 시각과 수신 시각 차) |
| M3. CPU 상한 `cpus`(CFS 할당량), 예: gateway 2.0 | Gateway 폭주가 reservation·DB·Kafka의 CPU를 잠식하는 것 | 2,000/s에서 gateway 188%라 2.0이면 거의 같은 처리량에서 묶인다. Gateway 처리 한도 ≈ 2.0코어 ÷ 0.95\~1.07ms = 약 1,870\~2,100/s. 나머지에 200% 이상 보장. JVM 21은 할당량을 보고 `availableProcessors`=2로 잡아 이벤트 루프·GC 스레드가 4 → 2로 준다(일반론) | 남는 CPU를 빌려 쓰지 못한다. CFS는 100ms 주기로 할당량을 넘으면 멈추게 해(throttling) 꼬리 지연이 튈 수 있다(일반론). 값을 잘못 잡으면 Gateway 한도를 스스로 낮춘다 | 인프라 | `/sys/fs/cgroup/.../cpu.stat`의 `nr_throttled`·`throttled_usec`, 굿풋, p99 |
| M4. 코어 고정 `cpuset`, 예: gateway `0-1`, 나머지 `2-3` | M3와 같고, 캐시·코어 경합까지 분리 | 효과 크기는 M3와 비슷할 것으로 본다(추정) | 쉬는 코어도 못 빌려 쓴다. Kafka 순간 140%가 DB와 같은 두 코어에 몰린다. 되돌리기 쉬우나 이득 대비 위험이 크다 | 인프라 | M3와 같음 |
| M5. 부가 JVM 줄이기: config·eureka·mock-pg·assistant에 `-XX:ActiveProcessorCount=1` | 피크와 무관한 JVM이 CPU 4개 기준 GC·JIT 스레드를 띄우는 것 | 이 컨테이너들 CPU는 각 5% 미만이라 이득은 작다(측정, 상한 계산 안 함) | 기동·처리가 느려질 수 있다. Gateway·reservation에는 쓰지 않는다 | 인프라 | 기동 시간, 쉬는 구간 컨테이너 CPU |

### 2-2. 유입 제어

| 방법 | 지키는 것 | 근거·효과(계산값) | 비용·위험 | 담당 | 확인 |
|---|---|---|---|---|---|
| **A1. Gateway 맨 앞 동시 처리 수 상한**(인증 필터 -100보다 앞, 세마포어, 넘치면 바로 503 + `Retry-After`) | **OOM과 CPU 포화를 한 번에.** 힙을 키우는 것이 "동시에 처리 중인 요청 수"이고(5차 해석), 동시 수를 묶으면 처리율도 리틀의 법칙으로 묶인다 | 리틀의 법칙 L = λ × W: 1,000/s × p95 15ms ≈ 15, 1,500/s × 152ms ≈ 228. 웜업을 버틴 활성 VU가 2,931, 죽은 값이 5,300 이상이므로 **첫 후보 500**(1,500/s 구간의 2배 여유, 생존값의 약 1/6). 처리율 상한보다 장비 변화에 덜 민감하다 | "초당 몇 건"으로 말하기 어렵다. 값이 작으면 정상 요청도 거절한다. k6 활성 VU와 Gateway 처리 중 수가 같다는 가정을 재야 한다. SSE·긴 요청이 있으면 따로 뺀다(확인 못 함) | Gateway 작업자(이번 `auth-redis` 흐름은 kangmin) + 인프라 리뷰 | 새 지표(허용·거절·현재 수), 5차 실패 조건에서 OOM 0, 단계별 굿풋이 1,470/s 근처에서 평평한지 |
| A2. Gateway 맨 앞 요청률 상한(Resilience4j `RateLimiter`, 경로 묶음별) | CPU 포화로 받은 요청까지 느려지는 것 | 이미 [요청률 제한 노트](../../learning/kangmin/qna/2026-11-11-gateway-rate-limiter-vs-breaker.md)에서 설계했다. 4차 기준 후보 1,500\~1,800/s였으나, 5차 0a는 1,000/s(p95 15ms, CPU 75%)와 1,500/s(p95 152ms, CPU 93%) 사이에 무릎이 있어 **1,200\~1,400/s**를 가리킨다. 두 날 값이 어긋나므로 같은 조건에서 다시 정한다 | 값이 장비·코드에 묶여 바뀔 때마다 다시 재야 한다. OOM은 직접 막지 못한다(도착률이 같아도 처리 시간이 늘면 동시 수가 는다) | 같음 | 같은 0a 조건, 상한 아래 단계의 p95가 1,000/s 수준인지 |
| A3. nginx `limit_req`·`limit_conn` | 실제 사용자 경로(80)의 마지막 방어 | 실제 사용자는 nginx(`frontend/nginx/default.conf`, keepalive 32)를 지난다. **부하 측정은 8080으로 Gateway에 바로 간다**(ADR 0046, `compose.deploy.yml` 주석). 그래서 지금 시나리오로는 효과를 재지 못한다 | nginx도 같은 장비 CPU를 쓴다. 전체 상한이라 Gateway 안 경로별 구분을 못 한다 | 프런트 이미지(`frontend/`) 소유자와 인프라 | 시연 경로(80)로 짧은 확인 측정 |

- A1 첫 값 정정(2026-11-11): 위 500은 p95를 평균 체류 시간 자리에 넣은 거친 값이다. 5차 단계별 평균 응답으로 다시 셈하면 p95 200ms를 지키는 처리 중 수는 1,480/s × 0.040\~0.045초 ≈ 60\~67이고, 처리 중 수가 260을 넘은 단계는 p95가 이미 435ms 이상이었다. 500이면 2,000/s에서도 거절하지 않아 OOM만 막는다. 그래서 기본값을 64로 두고 측정 모드에서 32·64·128·500을 비교해 확정한다. k6가 본 처리 중 수에는 Gateway가 받기 전 커널 대기가 섞여 있어 Gateway 안의 수는 더 작을 수 있다([open-peak-scenarios.md](open-peak-scenarios.md) "A1 첫 상한값 근거").

A1과 A2를 함께 쓰면 역할이 나뉜다. A1은 메모리(동시 수), A2는 지연(초당 도착 수)을 지킨다. 둘 중 하나만 고르면 **A1**이 OOM까지 막으므로 먼저다.

### 2-3. 요청당 비용 줄이기

| 방법 | 지키는 것 | 근거·효과(계산값) | 비용·위험 | 담당 | 확인 |
|---|---|---|---|---|---|
| C1. 요청 로그 줄이기(이미 있는 `iseonjwa.request-log.enabled=false`로 먼저 재기 → 성공 요청 표본만 남기기 등 절충) | 요청마다 이벤트 루프 안에서 로그를 만들고 쓰는 비용, dockerd·alloy 경로 비용 | `logback*.xml`이 없어 Spring Boot 기본 콘솔 출력이다(동기, 일반론). 이벤트 루프 70% 안에 로그 서식 만들기가 섞여 있다. alloy 0.17ms/건과 컨테이너 밖 52\~68%의 일부가 이 경로다(추정). 크기는 확인 못 함 | 요청 추적 대시보드(ADR 0045)가 약해진다. 운영에서 끄면 장애 분석이 어렵다 | Gateway 작업자, 관측 설계(인프라) | 같은 0a 조건에서 켬·끔 비교: 굿풋, gateway·alloy CPU, 컨테이너 밖 CPU |
| C2. 추적 샘플링: **시연 조건 정하기** | 측정 조건과 시연 조건이 다른 것 | 측정 모드는 0.01, 평시·시연은 1.0이다(`compose.measure.yml`, `compose.deploy.yml`). **지금 측정값은 시연 조건을 대표하지 않는다.** 1.0의 비용은 확인 못 함 | 0.1로 낮추면 시연의 "아무 요청이나 traceId로 Zipkin 열기"가 10건 중 1건만 된다. 샘플링되지 않은 요청에도 로그의 traceId가 남는지는 확인 못 함 | 인프라(시연 설계) | 1.0과 0.01을 같은 조건으로 한 번씩: 굿풋, gateway·zipkin CPU |
| C3. Gateway 필터 체인 줄이기 | 이벤트 루프 70%의 쓰임새 중 줄일 수 있는 것 | 5차 0b(JFR)가 이 측정이다. 지금은 쓰임새를 모른다 | 결과 전에는 고를 것이 없다 | Gateway 작업자 | 0b JFR 상위 5개 × 한 건당 1.07ms |
| C4. 폐기 목록 조회를 Gateway 메모리 캐시로(G-2) | 요청마다 Redis 한 번, Lettuce 스레드 비용 | Lettuce 20% × gateway 0.95ms ≈ 0.19ms + redis 14% ÷ 2,000건 ≈ 0.07ms → 약 0.26ms, 서버 한 건당 2.0ms의 약 13%(상한, 계산값) | **ADR 0005(fail closed)와 충돌할 수 있다.** 로그아웃 반영이 갱신 주기만큼 늦다. 보안 결정이라 이 문서에서는 표시만 한다 | 인증 설계(팀 합의) | 해당 없음(M4 범위 밖) |

### 2-4. 피크 때 덜 중요한 일 줄이기

| 방법 | 지키는 것 | 근거·효과(계산값) | 비용·위험 | 담당 | 확인 |
|---|---|---|---|---|---|
| B1. Kafka 쉬는 구간 순간 CPU의 원인 찾기 | 부하와 상관없는 고정 비용 중 가장 큰 것 | 쉬는 구간 최대 140%, S1에서 12초마다 90\~160%. 400% 중 최대 35%를 순간 점유(계산값). 원인 확인 못 함 | 원인을 모르고 몫만 낮추면 결제·추첨 이벤트가 늦어진다. Kafka는 낮추지 않는다 | 인프라 | 부하 없는 10분 동안 호스트 `pidstat -t`로 Kafka 스레드 이름별 CPU, Kafka 로그의 주기 작업 시각 |
| B2. 관측 수집 주기: alloy 컨테이너 탐색 5초, Prometheus 수집 5초 | 고정 비용 | alloy 22\~27%가 부하와 거의 무관하다(1절). 5초 탐색은 "뜬 지 1분 안에 죽는 컨테이너 로그를 놓치지 않으려는" 이유가 있다(`infra/alloy/config.alloy` 주석) | 탐색 주기를 늘리면 그 이유가 깨진다. Prometheus 주기를 늘리면 측정 해상도가 준다(측정 도구라 피크 때 줄이지 않는 것을 추천) | 인프라 | 부하 없는 구간 alloy CPU(지금 기록 없음) |
| B3. 피크와 무관한 컨테이너 끄기·낮추기: kafka-ui, assistant-service(, 샘플링 0.01일 때 zipkin) | 상주 스레드·GC·메모리 | CPU는 각 5% 미만(측정), 메모리 상한 합 kafka-ui 512 + assistant 384 = 896MiB(계산값) | 시연에서 assistant를 보여 주면 끌 수 없다. 측정 모드에서만 끄는 것이 현실적이다 | 인프라(측정 모드 스크립트) | 쉬는 구간·피크 컨테이너 CPU 합 |
| B4. reservation 배경 작업 주기 | 피크 중 DB·CPU 고정 비용 | 대기열 입장 500ms(핵심, 그대로), 좌석 선점 만료 5초, 추첨 배정·승계 1초, 마감·추첨 10초, 종료 1분, 회차 캐시 30초, outbox 릴레이 500ms(배치 20). 주기마다 드는 비용은 확인 못 함 | 추첨 주기를 늘리면 당첨 → 결제 시간이 늘어난다(추첨 숫자 기준과 충돌 가능). 피크 동안만 늦추는 스위치가 있는지 확인 못 함 | reservation 각 담당(추첨은 kangmin) | 부하 없는 구간 postgres-reservation CPU와 쿼리 수 |

### 2-5. 서비스 간 경계 유지

| 방법 | 지키는 것 | 근거·효과 | 비용·위험 | 담당 | 확인 |
|---|---|---|---|---|---|
| E1. 지금 있는 bulkhead·풀 분리 유지 | 한 외부 의존(Redis, PG, DB)의 지연이 스레드·연결을 다 먹는 것 | `auth-redis` 50, `pg-approve` 10, 추첨 자동결제 벌크헤드(ADR 0051), Hikari 5/5/20 | 이것들은 **호출 수**를 나눌 뿐 CPU는 나누지 않는다. CPU 칸은 M2·M3가 맡는다 | 각 서비스 | 유지 |
| E2. 공유 Redis의 용도 겹침 인지 | 대기열 몰림이 인증 조회를 느리게 하는 것 | Redis 한 대를 대기열·좌석 선점·인증 폐기 목록이 같이 쓴다. 지금 CPU 13\~15%라 병목은 아니다(측정) | 분리하면 메모리·운영이 늘어난다. 지금은 표시만 한다 | 인프라 | redis CPU, 명령별 지연(지표 없음, `redis_exporter` 없음) |
| E3. PostgreSQL 3개 유지 vs 하나로 합치기 | DB CPU 격리 | 11/11 S1에서 좌석 조회 초당 50건에 postgres-reservation 약 210%였다. 따로 두면 이 폭주가 결제 DB 프로세스와 다른 컨테이너다. 합치면 PostgreSQL 기본 기능에는 DB별 CPU 상한이 없다(일반론). 메모리 상한 합은 지금 2,048MiB(계산값) | 합치면 메모리·운영은 준다. 대신 좌석 조회 폭주가 결제 쿼리 지연으로 번질 길이 생긴다. 완화는 DB·역할별 연결 수 상한(`CONNECTION LIMIT`)과 `statement_timeout`이지만 CPU는 못 나눈다 | 인프라(준현님 계획) | 합치기 전후 S1 + 결제 동시 부하에서 결제 p95 |

## 3. 인프라 계획(준현님)과 겹치는 부분

원문을 보지 못했다. "7단계 실행 환경: 컨테이너별 CPU 몫, PostgreSQL 3개를 하나로"라는 한 문장만 참고했다.

| 이 문서 | 계획 | 관계 |
|---|---|---|
| M2 `cpu_shares`, M3 `cpus`, M4 `cpuset` | 컨테이너별 CPU 몫 | **겹친다.** 따로 제안하지 않고 그 계획에 측정 근거(1절 표, 컨테이너 밖 CPU)를 보태는 것이 맞다. 어느 방식(가중치·할당량·코어)인지는 확인 못 함 |
| E3 PostgreSQL 유지 | PostgreSQL 3개를 하나로 | **방향이 다르다.** 합치는 목적(메모리? 운영 단순화?)을 먼저 묻고, S1 측정(postgres-reservation 210%)을 근거로 CPU 격리가 사라지는 점을 함께 본다 |
| M1, A1, C1\~C2, B1\~B4 | 없음(확인 못 함) | 새 제안 |

## 4. 발표 관점: 한 장면

- 장면: "코드는 나뉘었지만 자원은 하나다." 4 vCPU 막대 하나에 gateway 50\~55%, reservation 23%, alloy 7\~10%, kafka·redis, 컨테이너 밖 13\~17%를 쌓고, 옆에 "Gateway OOM 3회"를 둔다. 그 아래 대응 세 가지(메모리 예산 맞춤, 맨 앞 동시 수 상한, CPU 가중치)와 각각의 전후 측정을 한 줄씩 둔다.
- 이 장면이 증명하는 것: 한 대 안에서도 서비스 경계를 자원 경계(메모리·CPU·유입량)로 옮길 수 있고, 무엇을 고를지는 측정으로 정했다.
- 예상 질문과 답 방향

| 질문 | 답 방향 |
|---|---|
| 한 대에 다 올리면 MSA가 무슨 의미인가 | 배포·장애·데이터 단위는 이미 서비스별로 나뉘어 있다. 컨테이너 한 개가 나중에 노드 한 대가 된다. 남은 것은 CPU와 유입량 경계이고, 그것을 이번에 채웠다 |
| 왜 서버를 늘리지 않았나 | 일정·비용 전제(ADR 0047). 대신 늘리면 무엇이 바뀌는지를 아래 "바꿀 조건"으로 정해 두었다 |
| 차단기가 있는데 왜 동시 수 상한이 또 필요한가 | 차단기는 Redis 조회 하나만 감싼다. 열려도 연결은 받으므로 메모리가 계속 는다(5차 해석) |

## 5. 추천 순서

기준: 효과 대비 비용, M4(11/16) 안에 가능한지, 측정으로 증명할 수 있는지, 팀 합의가 필요한지.

| 순위 | 방법 | 고른 이유 | 감수할 점 | 팀 합의 |
|---|---|---|---|---|
| 1 | **M1 Gateway 메모리 예산 맞추기** | 측정된 실패(OOM 3회)를 직접 막는다. 설정 몇 줄이고 5차 실패 조건 재실행 한 번으로 증명된다 | A안은 다른 몫 256MiB, B안은 GC 증가 가능. 유입을 안 묶으면 재발 여지가 남는다 | 인프라(`compose.deploy.yml`, contract 이슈) |
| 2 | **A1 Gateway 맨 앞 동시 수 상한** | OOM과 CPU 포화를 같이 막는 유일한 장치다. 의존성 없이 세마포어 하나로 된다 | 첫 값 500은 계산값이다. 측정으로 다시 정해야 한다 | Gateway 공유 설정이라 인프라 리뷰 |
| 3 | **측정 한 회로 고정 비용 정체 찾기**(C1 요청 로그 켬·끔, B1 Kafka 쉬는 구간, 컨테이너 밖 CPU를 호스트 `pidstat`으로) | 비용이 거의 없고, M2·C1·B2의 효과 크기를 정한다. 5차 0b(JFR)와 같은 회에 묶을 수 있다 | 처리량을 바로 올려 주지 않는다 | 없음(측정만) |
| 4 | **M2 `cpu_shares` 가중치**(관측·관리 낮춤) | 남는 CPU를 빌려 쓰므로 실패 모드가 가장 순하다. 준현님 계획과 겹쳐 합쳐서 갈 수 있다 | 로그·알림 지연. 이득 상한이 초당 100\~125건(계산값)으로 작다 | 인프라 계획과 합침 |
| 5 | **C2 시연 추적 샘플링 정하기** | 지금 측정은 0.01, 시연은 1.0이라 측정값이 시연을 대표하지 못한다. 결정 하나로 측정과 시연을 맞춘다 | 0.1이면 시연의 traceId 링크가 일부만 된다 | 인프라(시연 설계) |

M4 안에 고르지 않은 것: M3·M4(`cpus`·`cpuset`, 3번 결과를 본 뒤), E3 PostgreSQL 합치기(격리 측정 먼저), C4 폐기 목록 캐시(보안 합의 먼저), A3 nginx 제한(측정 경로가 아님).

## 6. 바꿀 조건(서버를 나눈다면)

| 조건 | 바뀌는 것 |
|---|---|
| Gateway를 다른 노드로(또는 여러 대로) | A1·A2 상한이 노드 수만큼 커진다(로컬 카운터). M3의 Gateway 몫 다툼이 사라진다. 한 건당 CPU 측정은 그대로 쓸 수 있다 |
| 관측 도구를 다른 노드로 | alloy·loki·zipkin·Prometheus 몫이 사라진다. 컨테이너 밖 CPU 중 로그 전달 몫이 줄어드는지 다시 잰다 |
| DB를 RDS로(ADR 0047 3번) | E3 논의가 "인스턴스 몇 개"로 바뀐다. 앱 노드 CPU에서 postgres 몫(S1 210%)이 빠진다 |
| 3번 측정에서 요청당 비용이 크게 준다 | A2 상한을 다시 잰다. M2·M3의 필요성이 줄 수 있다 |
| 동시 수 상한을 넣고도 OOM이 난다 | JVM 밖 메모리(Netty 직접 메모리) 측정을 먼저 한다(`reactor.netty` 할당기 지표) |

## 7. 확인 못 함 / 확인할 질문

- Gateway JVM 밖 메모리 약 130MB의 정체(Netty 풀 직접 메모리로 추정). JRE 이미지에 `jcmd`가 없어 NMT 조회가 어렵다
- 컨테이너 밖 CPU(약 52\~68%)를 누가 쓰는지(dockerd 로그 전달, 커널 네트워크 처리, 측정 수집기 중 어느 쪽인지)
- alloy·Kafka가 부하와 무관하게 쓰는 CPU의 원인
- 추적 샘플링 1.0과 0.01의 처리량 차이, 샘플링되지 않은 요청의 로그에 traceId가 남는지
- 동시 수 상한 500에서 실제 거절률과 굿풋(계산값만 있음)
- 4차와 5차의 같은 2,000/s p95 차이(157ms vs 435ms) 원인. A2 상한값이 여기에 달려 있다
- reservation 배경 작업 주기별 DB 비용, 피크 동안 늦추는 스위치 유무
- 인프라 계획 원문(CPU 몫 방식, PostgreSQL 합치는 목적)

## 원천

- 측정: [open-peak-scenarios.md](open-peak-scenarios.md) "배포 서버 5차"·"배포 서버: S1 좌석 조회", [gateway-auth-redis-resilience.md](gateway-auth-redis-resilience.md) 5-2·7절, [lottery/numbers-and-measurement.md](../lottery/numbers-and-measurement.md) 3-3·3-4절
- 설정: `compose.yml`, `compose.deploy.yml`, `loadtest/deploy/compose.measure.yml`, `infra/alloy/config.alloy`, `infra/prometheus.yml`(수집 5초), `config-repo/application.yml`(샘플링 기본 0.1), `config-repo/*-service.yml`(Hikari, outbox, queue 주기), `frontend/nginx/default.conf`, `Dockerfile`(JRE 21), `gateway/.../RequestLogWebFilter.java`
- 결정: [ADR 0005](../../decisions/0005-auth-redis-fail-closed.md), [ADR 0047](../../decisions/0047-single-ec2-first-managed-split-review.md), [ADR 0050](../../decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md), [ADR 0051](../../decisions/0051-lottery-billing-consumer-concurrency-and-billing-bulkhead.md)
- 학습 노트(kangmin): [가벼운 컨테이너](../../learning/kangmin/qna/2026-11-10-lighten-containers-on-shared-server.md), [Gateway 스레드·벌크헤드·서버 사양](../../learning/kangmin/qna/2026-11-10-gateway-threads-bulkhead-vs-server-spec.md), [CPU 포화 개선](../../learning/kangmin/qna/2026-11-11-gateway-cpu-saturation-improvements.md), [요청률 제한 vs 차단기](../../learning/kangmin/qna/2026-11-11-gateway-rate-limiter-vs-breaker.md), [차단기 vs CPU 포화](../../learning/kangmin/qna/2026-11-11-breaker-vs-cpu-saturation.md)
