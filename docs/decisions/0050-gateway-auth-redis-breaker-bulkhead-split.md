# 0050. Gateway auth-redis 차단기는 Redis 장애만 세고, 자기 과부하는 bulkhead가 거절하며 거절 비율은 경보로 따로 본다

- 상태: 제안 (ISE-230 contract. 대기열 담당 확인 뒤 채택)
- 날짜: 2026-11-11
- 결정자: 이강민(사용자 결정), 근거 측정 권영은·이강민
- 관련 AI 판단: [운영값과 거절 경보 선택](../ai-judgments/kangmin/2026-11-11-gateway-auth-redis-operating-values.md), [11/10 재측정 값](../ai-judgments/kangmin/2026-11-10-auth-redis-timeout-bulkhead-measure-values.md)
- 관련 설계: [gateway-auth-redis-resilience.md](../design/infra/gateway-auth-redis-resilience.md), [open-peak-scenarios.md](../design/infra/open-peak-scenarios.md) 배포 서버 1~3차
- 대체하는 SA 항목: SA MASTER 9-4 표의 "Redis 호출 타임아웃 100ms", "Redis 서킷 브레이커(최근 20회 중 50% 실패 또는 50ms 초과, 10초 뒤 HALF_OPEN 5회)", 9-5의 "모든 Redis 호출에 타임아웃 100ms" 중 **Gateway 블랙리스트 확인(`auth-redis`)만**. Reservation의 Redis 호출과 Notion SA는 바꾸지 않았다(대전제 4)

## 맥락
- 로그아웃 블랙리스트 확인은 Redis가 응답하지 않으면 요청을 막는다(fail closed, [ADR 0005](0005-auth-redis-fail-closed.md)). 이 확인을 Resilience4j 차단기 `auth-redis`와 bulkhead가 감싼다. 감싸는 순서는 바깥부터 TimeLimiter(1초) → 차단기 → bulkhead → Redis 조회다(spring-cloud-circuitbreaker-resilience4j 5.0.3 소스로 확인).
- 11/9~10 배포 측정에서 Redis는 멀쩡한데(Redis CPU 8~17%, 명령 0.1~2ms) 차단기가 먼저 열렸다. 열리면 인증 요청 전체가 열림 시간 동안 503이다.
  - 1차 Q1(운영 설정): 데운 초당 2,000~3,000명에서 2ms 안 빠른 실패로 열렸다. Q2 10,000명은 열린 뒤 600초 동안 회복하지 못했다(503 98.5%).
  - 3차(권영은, 원인 지표 추가 뒤): 열린 원인 대부분이 bulkhead 거절(`bulkhead_full` 10~17건)이었다. bulkhead가 차단기 안쪽이라 거절이 차단기 실패로 세어졌다. 건수 창 20은 초당 1,000건에서 0.02초라, 거절 10건이면 열렸다.
  - 2차의 "1.0초 실패"는 안쪽 시한 초과가 두 번 기록된 것이었다(ISE-230 #463에서 고침).
- 차단기 타이머는 Gateway 안에 있어 Gateway CPU 대기가 섞인다. 그래서 Gateway 과부하가 Redis 장애로 보일 수 있다.
- 운영 기본값은 SA 9-4 값 그대로였다(건수 창 20, 느린 호출 50ms·50%, 열림 10초, 반열림 5, bulkhead는 라이브러리 기본 25, 시한 100ms). 근거 측정은 없었다.

## 결정
1. **역할을 나눈다.** 차단기는 Redis 장애(시한 초과 `lookup_timeout`, 연결 오류 `redis_error`, 바깥 `time_limiter`)만 센다. Gateway 자기 과부하는 bulkhead가 넘친 만큼만 바로 거절한다(503). bulkhead 거절(`BulkheadFullException`)은 차단기 실패에서 뺀다(`ignore-exceptions`).
2. **거절 비율은 경보로 따로 본다.** 거절을 빼면 과부하가 차단기 상태로 보이지 않는다. 그래서 auth-redis 호출 중 bulkhead 거절 비율을 두 규칙으로 본다(`infra/prometheus/rules/gateway-alerts.yml`, 둘 다 warning).
   - 지속 과부하 `GatewayAuthRedisBulkheadRejecting`: 5분 평균 1% 초과가 5분 이어지면 울린다. 오픈 피크(수십 초~1분)는 평균에 묻혀 잡지 못한다.
   - 오픈 피크 `GatewayAuthRedisBulkheadRejectingPeak`: 1분 평균 1%를 넘으면 바로 울린다. 20초 안팎보다 짧은 몰림은 여전히 못 잡는다(3차 G의 10초·3%는 1분 평균 약 0.5%, 계산값). 대기열 담당 리뷰(PR `#487`)로 더했다.
   - 1%는 `[가정]`이다. G1 재측정 2회에서 초당 2,000건의 거절이 1.1%였다.
   - bulkhead가 차는 원인은 Gateway 과부하만이 아니다. Redis가 느려지면 한 호출이 자리를 필터 시한(200ms)까지 붙잡아 초당 50 ÷ 0.2 = 250건만 지나간다(계산값). 그래서 경보 문구는 원인을 단정하지 않고, `lookup_timeout`·`redis_error`가 함께 늘었으면 Redis 지연으로 보라고 안내한다.
3. **운영값**(`config-repo/gateway.yml`)

| 값 | 이전(SA 9-4) | 새 값 | 근거 측정 |
|---|---|---|---|
| 창 | 건수 20, 최소 20 | 시간 10초, 최소 500건 | 3차 R3(초당 2,000명까지)·G(3,000명까지), G1 1·2회(3,000/s까지) 모두 열리지 않음 |
| 실패율 | 50% | 50% | 그대로. 실패의 뜻이 Redis 장애로 좁아진다 |
| 느린 호출 | 50ms·50% | 100ms·80% | 1차 B0m·3차 R1: 50ms는 식은 Gateway 첫 몰림(Redis 0.1~0.8ms)에서 느린 호출로 열림 |
| 열림·반열림 | 10초·5건 | 5초·20건 | 3차 F Q2 30,000명: 10초 열림 → 반열림 5건 → 닫힘 → 쌓인 요청으로 다시 열림을 9회 되풀이. 3차 G는 0회 |
| bulkhead | 25(기본값), 대기 0 | 50, 대기 0 | 3차 R1·R2: 25는 초당 300~1,000명에서 거절로 차단기를 엶. 3차 R3(100)는 식은 1,000명에서 시한 초과 2,469건, G(50)는 0건. G1 2회: 50이 100보다 p95를 줄임(2,000/s 208 → 157ms) |
| 필터 시한(`auth.redis-timeout`) | 100ms | 200ms | G1 1·2회 시한 초과 0건. 느린 호출 기준과 같으면 시한 초과가 실패와 느린 호출로 함께 세어진다(3차 F 식은 1,000명: 느린 호출 20/20 중 실패 10) |
| Lettuce 명령 시한(`spring.data.redis.timeout`) | 100ms(공통) | 300ms(Gateway만) | 필터 시한보다 커야 원인이 `lookup_timeout`으로 남는다. 다른 서비스는 공통 100ms 그대로 |

4. 시한의 순서를 지킨다: Lettuce 300ms > 필터 200ms > 느린 호출 100ms > 정상 응답(약 1~7ms). 이 순서는 테스트로 묶었다(`AuthRedisResilienceContextTest`의 `운영_시한은_...` 테스트).
5. 측정 덮어쓰기(`loadtest/deploy/compose.gateway-bulkhead.yml`)의 기본값을 새 운영값과 같게 맞춘다. 값을 안 주면 운영과 같은 조건으로 잰다.

## 검토한 대안
- **거절을 실패로 센다(1회 설정, 이전 동작)**: 과부하 때 차단기가 열려 "넘친 만큼"이 아니라 "전부"를 거절한다. 1차·3차에서 이 모양으로 인증 전체가 503이 됐고, Q2에서는 재시도 폭주로 번졌다.
- **차단기 기준을 좁혀 CPU 포화도 잡게 한다**: Redis 문제와 Gateway 문제를 구별하지 못한다. 위와 같은 전면 거절과 열림·닫힘 진동으로 돌아간다([학습 노트](../learning/kangmin/qna/2026-11-11-breaker-vs-cpu-saturation.md)).
- **bulkhead를 차단기 바깥으로 옮긴다(C1)**: 구조로 집계를 나눌 수 있다. 하지만 spring-cloud-circuitbreaker가 정한 감싸는 순서를 바꾸려면 필터에서 Resilience4j를 직접 써야 해 코드 변경이 크다. 같은 효과를 `ignore-exceptions` 설정 하나로 얻는다. 바꿀 조건에 남긴다([학습 노트](../learning/kangmin/qna/2026-11-11-bulkhead-rejection-breaker-count.md)).
- **필터 시한 100ms 유지(3차 G 값)**: SA와 같고 G 데운 2,000/s 4회 근거가 있다. 하지만 느린 호출 기준과 같아 시한 초과가 두 번 세어진다. 느린 호출 기준을 낮추면 다시 식은 Gateway에서 열린다.
- **Gateway 입구 요청률 제한**: 넘친 양을 더 싸게 거절한다. 3차 G 초당 3,000명에서 실패 6,934건 중 bulkhead가 거절한 것은 59%뿐이었다. 하지만 고정값이 장비에 묶이고, 기본 `RequestRateLimiter`는 Redis를 써 Redis 부하가 늘어난다. 바꿀 조건에 남긴다.

## 결과
- 얻는 것: Redis가 멀쩡하면 차단기가 열리지 않는다(G1 2회 거절 16,024건 모두 `ignored`, `failed` 0, 3,000/s까지 closed). 차단기 상태가 Redis 상태를 뜻하게 된다.
- 감수하는 것
  - Redis가 실제로 느려져도 감지가 늦다(느린 호출 100ms·80%, 최소 500건). 초당 50건 미만이면 10초 창이 차지 않아 차단기가 판정하지 않는다.
  - 진짜 Redis 멈춤 때 사용자가 503까지 최대 200ms 기다린다. SA 9-5의 "100ms는 이상 징후"보다 길다.
  - 반열림 20건이라 진짜 장애 때 시험 호출이 더 나간다.
  - 서버 CPU 한도(4 vCPU 한 대에서 Q1 초당 약 2,000~2,300건)는 그대로다. 그 이상에서는 거절은 빠르지만 받은 요청도 느리다(G1 2회 2,500/s p95 428ms, 3,000/s p99 7.2s).
  - 모든 근거가 각 1회 측정이다(3차 G 데운 2,000/s만 4회). bulkhead 50은 계산값(초당 1,000건 × 50ms)으로 시작했다.
  - Lettuce 300ms가 반응형 호출에 걸리는지는 간접 근거만 있다. 3차 R3(필터 시한·Lettuce 모두 100ms)에서 Gateway `redis_error`가 났고(식은 1,000명 131건), 대기열 담당이 로그에서 `QueryTimeoutException` 106~145ms를 봤다(PR `#487` 리뷰, 저장소 기록에는 예외 종류·시간이 없다). 300ms로 바꾼 뒤 직접 확인은 아직이다.
- 바꿀 조건
  - 피크 경보(`GatewayAuthRedisBulkheadRejectingPeak`)가 오픈마다 울리면 입구 요청률 제한(인스턴스 로컬)을 넣는다. 기준은 혼합 부하(진입 + 폴링) 측정의 통과선이다.
  - 장비나 Gateway 인스턴스 수가 바뀌면 bulkhead를 리틀의 법칙(목표 처리량 × 정상 한 건 시간)으로 다시 낸다.
  - `ignore-exceptions`로 부족한 경우(예: 거절 외 Gateway 혼잡이 시한 초과로 세어져 열림)가 측정되면 bulkhead를 차단기 바깥으로 옮긴다.
  - Redis 장애 감지가 늦어 문제가 되면 느린 호출 비율·최소 건수를 낮춘다. 장애 주입(Redis 지연)으로 감지 시간을 먼저 잰다.
- 후속
  - 배포 뒤 측정 모드 없이 G1 또는 Q1로 한 번 재서 운영값이 덮어쓰기 측정과 같은지 확인한다. 대기열 담당이 2026-11-15에 Q1·Q2-10k·Q1-d로 Gateway 덮어쓰기 없이 잰다(PR `#487` 리뷰). 이때 피크 경보가 울리는지도 본다.
  - Retry-After 분리(ISE-230 3번)는 따로 한다.
  - 차단기 열림 경보(Redis 장애)는 이번에 넣지 않았다.
