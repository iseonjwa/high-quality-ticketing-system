# kangmin 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-11-12 | 겹겹이 걸린 시한은 각각 어디서부터 재고 어떤 순서로 터져야 원인을 알 수 있나(Gateway 인증 계단 100·200·300·1000ms, 시계 시작점, 안 걸리는 시한의 뜻, `SLOW_ERROR` 이중 집계, Gateway 응답 시한 범위, config-repo 시한 지도, 흐름별 시한 예산) | [qna/2026-11-12-timeout-layers-and-config-repo-map.md](qna/2026-11-12-timeout-layers-and-config-repo-map.md) |
| 2026-11-12 | 선점 성공 시 입장 토큰을 무효화하려면 어떻게 처리해야 하나(JWT는 만료시킬 수 없다, 비교 위치 G·S·Q, 선점 API DB 부분 고유 인덱스 + issued ZREM, 담당별 할 일, 거절 코드, 테스트) | [qna/2026-11-12-entry-token-consume-on-hold-implementation.md](qna/2026-11-12-entry-token-consume-on-hold-implementation.md) |
| 2026-11-12 | 입장 토큰은 언제 소모해야 하나, 결제 토큰이 먼저 만료되면 어떻게 동작하나(세 가지 TTL, 403 PAYMENT_TOKEN_INVALID 뒤 좌석·화면, 자기 선점에 막히는 빈틈, 소모 시점 A·A′·B·C 비교와 정할 것) | [qna/2026-11-12-entry-token-consume-timing-and-payment-token-expiry.md](qna/2026-11-12-entry-token-consume-timing-and-payment-token-expiry.md) |
| 2026-11-12 | 벌크헤드와 동시 처리 수 상한은 무엇이 다른가(감싸는 범위·위치·지키는 것, 건물 정문과 엘리베이터 비유, 요청이 지나는 순서, 둘 다 필요한 이유, 차단기와 구분, 값 정하기) | [qna/2026-11-12-bulkhead-vs-in-flight-limit.md](qna/2026-11-12-bulkhead-vs-in-flight-limit.md) |
| 2026-11-12 | 예전에는 거절 한 건도 받은 요청만큼 비쌌고 H1로 싸게 만든 것이 맞나(H1 전 약 1/5 추정, H1 뒤 2~8% 측정, 포화 구간 지표 한계, 이력서에 쓸 수 있는 말과 없는 말) | [qna/2026-11-12-rejection-cost-before-after.md](qna/2026-11-12-rejection-cost-before-after.md) |
| 2026-11-12 | 동시 수 상한으로도 CPU 포화가 안 풀리면 어떻게 하나, 로드밸런서가 필요한가(작업 보존형, 효율·수요·용량 세 방향, 폴링 간격 계산, 한 대 안 로드밸런서가 소용없는 이유, 남은 트러블슈팅) | [qna/2026-11-12-gateway-cpu-saturation-next-steps.md](qna/2026-11-12-gateway-cpu-saturation-next-steps.md) |
| 2026-11-11 | 7차 H1 재해석: 굿풋 +12.8%인데 한 건당 CPU가 그대로인 이유(포화 구간 지표 한계, 성공 한 건당 CPU, 받은·거절 한 건 비용 연립 계산, 동시 수 상한은 작업 보존형), 다른 AI 분석 판정(Netty 파싱 뒤 실행, 카운터·503 쓰기 비용, 이벤트 루프 자료 수집 오류), 다음 가설 | [qna/2026-11-11-h1-reinterpret-netty-cost.md](qna/2026-11-11-h1-reinterpret-netty-cost.md) |
| 2026-11-11 | HttpHandler 데코레이터로 동시 처리 수 상한을 관측보다 앞에서 거절하는 원리(Netty부터 GlobalFilter까지 층별 소스 위치, 데코레이터 패턴과 `defer`·`doFinally` 반납, WebFilter·ObservationPredicate·Netty 단계와 비교, 거절에서 사라지는 기록, 관리 포트 무영향, 7차 확인 지표) | [qna/2026-11-11-gateway-httphandler-decorator-early-reject.md](qna/2026-11-11-gateway-httphandler-decorator-early-reject.md) |
| 2026-11-11 | H1의 "관측 비용 가설"은 무엇이고 HttpHandler 데코레이터는 왜 "부담"이라고 했나(요청이 지나는 층 순서, Boot 4 서버 관측 위치, WebFilter안과 데코레이터안 비교, 거절된 요청만 잃는 것) | [qna/2026-11-11-observation-cost-hypothesis-and-handler-decorator.md](qna/2026-11-11-observation-cost-hypothesis-and-handler-decorator.md) |
| 2026-11-11 | application 계층은 HTTP를 몰라야 하는데 왜 에러 코드(HTTP 상태 포함)가 application에 있나(컴파일 의존과 의미 의존, 실패 종류 매핑 대안, 바꿀 조건) | [qna/2026-11-11-error-code-http-status-in-application.md](qna/2026-11-11-error-code-http-status-in-application.md) |
| 2026-11-11 | `resilience4j.circuitbreaker.instances.auth-redis` 값은 코드 어디서 읽나(`@Value`가 아닌 자동 구성·`@ConfigurationProperties` 바인딩, 등록소 이름 조회, 재기동해야 반영되는 이유) | [qna/2026-11-11-resilience4j-config-binding.md](qna/2026-11-11-resilience4j-config-binding.md) |
| 2026-11-11 | 스케줄러 쪽 추적 스팬을 끄면 무엇을 못 보게 되나(끄는 범위별 손실, Kafka로 이어지는 추적, 관측과 스팬 구분) | [qna/2026-11-11-scheduler-span-off-what-we-lose.md](qna/2026-11-11-scheduler-span-off-what-we-lose.md) |
| 2026-11-11 | bulkhead 거절 경보의 "1%·5분"은 무슨 뜻이고 슬랙 메시지는 언제 몇 번 가나(`rate`·`for`, `group_wait`·`repeat_interval`, 피크 규칙) | [qna/2026-11-11-bulkhead-rejecting-alert-timing.md](qna/2026-11-11-bulkhead-rejecting-alert-timing.md) |
| 2026-11-11 | 요청률 제한으로 "차단기를 예민하게 세팅해 CPU 포화 전에 열기"의 약점을 줄일 수 있나(넘친 만큼만 거절, 인증 앞 로컬 제한기, 버스트 크기, 503·`Retry-After`, 비교 실험) | [qna/2026-11-11-gateway-rate-limiter-vs-breaker.md](qna/2026-11-11-gateway-rate-limiter-vs-breaker.md) |
| 2026-11-11 | 결제 pg-approve 동시 10의 근거와 높일 때의 효과·대가(리틀의 법칙, 장애 피해 상한) | [qna/2026-11-11-pg-approve-concurrency-10-basis.md](qna/2026-11-11-pg-approve-concurrency-10-basis.md) |
| 2026-11-11 | 추첨 자동결제 경로(배정 워커·outbox·릴레이·Kafka·결제 소비자·PG 허가·PG 호출) 키워드별 설명 | [qna/2026-11-11-lottery-autopay-pipeline.md](qna/2026-11-11-lottery-autopay-pipeline.md) |
| 2026-11-11 | 추첨 당첨 예매의 선점 만료는 왜 30분인가(자동결제 최악 확정 시간 예산, 연장·상한 60분) | [qna/2026-11-11-lottery-hold-30min-basis.md](qna/2026-11-11-lottery-hold-30min-basis.md) |
| 2026-11-11 | Gateway CPU 포화는 사양 문제일 뿐인가, 요청률 제한이 도움이 되나(프로파일링·요청당 CPU 줄이기·입구 제한) | [qna/2026-11-11-gateway-cpu-saturation-improvements.md](qna/2026-11-11-gateway-cpu-saturation-improvements.md) |
| 2026-11-11 | 벌크헤드 거절(503)을 auth-redis 차단기에서 예외로 뺄까, 실패로 세어 차단기를 열게 할까(Hystrix 기본값과 전제, 실패 표) | [qna/2026-11-11-bulkhead-rejection-breaker-count.md](qna/2026-11-11-bulkhead-rejection-breaker-count.md) |
| 2026-11-11 | 장비 CPU가 한도에 닿기 전에 차단기가 열려야 하지 않나(차단기 vs 부하 차단·유입 제어) | [qna/2026-11-11-breaker-vs-cpu-saturation.md](qna/2026-11-11-breaker-vs-cpu-saturation.md) |
| 2026-11-10 | dev→main PR은 왜 충돌했고, 동기화 PR로 어떻게 풀었나(squash 머지와 공통 조상, main 머지로만 배포) | [qna/2026-11-10-squash-merge-dev-main-conflict.md](qna/2026-11-10-squash-merge-dev-main-conflict.md) |
| 2026-11-10 | CPU·메모리가 빡빡한 배포 서버에서 컨테이너를 가볍게 만들어 해결할 수 있나 | [qna/2026-11-10-lighten-containers-on-shared-server.md](qna/2026-11-10-lighten-containers-on-shared-server.md) |
| 2026-11-10 | Gateway 스레드·벌크헤드·연결 풀 수치가 4 vCPU 배포 서버 사양을 고려해 정한 값인지, CPU 100%·과수용·차단기 시간 측정 주체·시한과 벌크헤드 재측정 값까지(16개 절) | [qna/2026-11-10-gateway-threads-bulkhead-vs-server-spec.md](qna/2026-11-10-gateway-threads-bulkhead-vs-server-spec.md) |
| 2026-11-10 | F1-a에서 결제 서버를 죽여도 NOT_SENT가 거의 안 생기는 이유와 테스트로 대신 보여 주는 근거 | [qna/2026-11-10-f1a-not-sent-kill-timing.md](qna/2026-11-10-f1a-not-sent-kill-timing.md) |
| 2026-11-10 | Gateway 벌크헤드·차단기에 걸려 대기열 진입이 503이면 사용자가 다시 눌러야 하나, 자동으로 들어가나 | [qna/2026-11-10-queue-join-503-auto-retry.md](qna/2026-11-10-queue-join-503-auto-retry.md) |
| 2026-11-10 | Resilience4j 서킷 브레이커와 벌크헤드 설정 키는 각각 무엇을 정하나 | [qna/2026-11-10-circuit-breaker-config-keys.md](qna/2026-11-10-circuit-breaker-config-keys.md) |
| 2026-11-10 | Gateway auth-redis 차단기·벌크헤드 권장 설정은 무엇을 바꾸고, 바꾸면 어떤 문제가 생기나 | [qna/2026-11-10-auth-redis-breaker-bulkhead-tradeoffs.md](qna/2026-11-10-auth-redis-breaker-bulkhead-tradeoffs.md) |
| 2026-11-10 | 웜업과 단계 크기는 결과를 바꾸나, auth-redis 서킷 브레이커는 어떻게 완화하고 Gateway는 어떻게 개선하나 | [qna/2026-11-10-circuit-breaker-warmup-and-relaxing.md](qna/2026-11-10-circuit-breaker-warmup-and-relaxing.md) |
| 2026-11-10 | 측정 도구 변경은 언제 커밋·푸시하고, main에 넘겨야 하나 | [qna/2026-11-10-measurement-tool-branch-vs-main.md](qna/2026-11-10-measurement-tool-branch-vs-main.md) |
| 2026-11-10 | 배포는 무엇으로 하나, 쿠버네티스가 아니라 EC2에 그냥 띄우는 것인가 | [qna/2026-11-10-deploy-method-ec2-compose-vs-k8s.md](qna/2026-11-10-deploy-method-ec2-compose-vs-k8s.md) |
| 2026-11-10 | Jenkins 서버가 꺼질 일이 있나, 접속이 안 되면 꺼진 것인가 | [qna/2026-11-10-when-jenkins-stops.md](qna/2026-11-10-when-jenkins-stops.md) |
| 2026-11-10 | 발표 대비 리뷰 1. 서비스 구성과 경계는 왜 이렇게 나눴나 | [qna/2026-11-10-review-01-structure-and-boundaries.md](qna/2026-11-10-review-01-structure-and-boundaries.md) |
| 2026-11-09 | Gateway에 벌크헤드 설정이 없으면 기본값이 걸리나 | [qna/2026-11-09-gateway-default-bulkhead.md](qna/2026-11-09-gateway-default-bulkhead.md) |
| 2026-11-09 | M4 할 일 목록의 F1은 무엇이고 왜 하나 | [qna/2026-11-09-what-is-f1-fault-injection.md](qna/2026-11-09-what-is-f1-fault-injection.md) |
| 2026-11-09 | 측정 모드는 왜 측정이 끝나면 꺼야 하나 | [qna/2026-11-09-why-turn-off-measure-mode.md](qna/2026-11-09-why-turn-off-measure-mode.md) |
| 2026-11-09 | 같은 셸 스크립트가 Windows Git Bash에서 왜 훨씬 느린가(프로세스 생성 비용) | [qna/2026-11-09-windows-git-bash-process-cost.md](qna/2026-11-09-windows-git-bash-process-cost.md) |
| 2026-11-09 | 측정 모드(measure-mode.sh)는 왜 AWS 담당만 켜나 | [qna/2026-11-09-why-measure-mode-aws-owner-only.md](qna/2026-11-09-why-measure-mode-aws-owner-only.md) |
| 2026-11-09 | IntelliJ가 shellcheck 설치를 물을 때 설치해도 되나 | [qna/2026-11-09-intellij-shellcheck-prompt.md](qna/2026-11-09-intellij-shellcheck-prompt.md) |
| 2026-11-09 | "운영 관리자 비밀번호를 발생기에 두지 않는다" 규칙의 배경과, 서버 추첨 생성(1안)이 남기는 위험 | [qna/2026-11-09-loadgen-admin-password-rule-risk.md](qna/2026-11-09-loadgen-admin-password-rule-risk.md) |
| 2026-11-09 | L1·L2 k6 스크립트의 관리자 로그인 문제를 풀 때 왜 계정 담당 동의가 필요한가 | [qna/2026-11-09-l1-l2-admin-login-consent.md](qna/2026-11-09-l1-l2-admin-login-consent.md) |
| 2026-11-09 | herdr로 에이전트 여러 개를 한 저장소에서 동시에 굴리려면 무엇을 지켜야 하나 | [qna/2026-11-09-herdr-parallel-agents.md](qna/2026-11-09-herdr-parallel-agents.md) |
| 2026-11-09 | timedatectl·chronyc tracking 실행은 시계를 맞춘 것인가, 상태만 본 것인가 | [qna/2026-11-09-chrony-check-vs-sync.md](qna/2026-11-09-chrony-check-vs-sync.md) |
| 2026-11-09 | 입장 토큰을 좌석 선점 시점과 결제 완료 시점 중 언제 만료시킬까 | [qna/2026-11-09-entry-token-early-expiry.md](qna/2026-11-09-entry-token-early-expiry.md) |
| 2026-11-09 | 튜터가 던진 세 이슈(NTP 시간 동기화, 레이트 리미터, 벌크헤드 격리)는 이 프로젝트 어디에 걸리나 | [qna/2026-11-09-tutor-issues-ntp-ratelimiter-bulkhead.md](qna/2026-11-09-tutor-issues-ntp-ratelimiter-bulkhead.md) |
| 2026-11-09 | Bulkhead·Circuit Breaker 말고 어떤 장애 대응 장치가 있고, 이 프로젝트는 무엇을 어디에 쓰나 | [qna/2026-11-09-resilience-patterns-map.md](qna/2026-11-09-resilience-patterns-map.md) |
| 2026-11-09 | 추첨 자동결제와 일반 결제가 겹칠 때 쓰는 장애 대응 장치와 개선 후보(레이트 리미터 도입 가능 여부 포함) | [qna/2026-11-09-billing-vs-general-payment-overlap.md](qna/2026-11-09-billing-vs-general-payment-overlap.md) |
| 2026-11-09 | 사용자가 프론트에서 추첨 화면을 찾아 들어갈 수 없는 이유와 고칠 방법 | [qna/2026-11-09-lottery-page-no-entry-point.md](qna/2026-11-09-lottery-page-no-entry-point.md) |
| 2026-11-09 | ADR 0047의 "RDS 분리"는 무엇이고, 왜 추첨 측정 전에 정해야 하나 | [qna/2026-11-09-rds-split-and-measurement.md](qna/2026-11-09-rds-split-and-measurement.md) |
| 2026-11-09 | 응답 시간의 p50·p95·p99는 무슨 뜻이고, 왜 평균 대신 쓰나 | [qna/2026-11-09-percentile-p50-p95-p99.md](qna/2026-11-09-percentile-p50-p95-p99.md) |
| 2026-11-09 | L1 응모 몰림 로컬 측정은 어떻게 돌렸고, 숫자는 무엇을 말하나 | [qna/2026-11-09-l1-local-measurement-detail.md](qna/2026-11-09-l1-local-measurement-detail.md) |
| 2026-11-09 | M4 주간 추첨 담당 할 일은 각각 무엇이고 왜 하는가 | [qna/2026-11-09-m4-my-tasks-explained.md](qna/2026-11-09-m4-my-tasks-explained.md) |
| 2026-11-08 | 로그인 토큰은 어디서 발급되고, 측정용으로 대량으로 받으려면 무엇을 거치나 | [qna/2026-11-08-login-token-issue-flow.md](qna/2026-11-08-login-token-issue-flow.md) |
| 2026-11-08 | ISE-201의 "토큰 발급 도구는 ISE-25 산출물을 쓴다"는 무슨 뜻이고, 추첨 측정에는 어떤 영향이 있나 | [qna/2026-11-08-ise201-token-tool-origin.md](qna/2026-11-08-ise201-token-tool-origin.md) |
| 2026-11-08 | AWS 배포에 나오는 용어를 하나씩 풀면 무엇이고, 이 프로젝트에서는 어디에 쓰이는가 | [qna/2026-11-08-aws-deploy-glossary.md](qna/2026-11-08-aws-deploy-glossary.md) |
| 2026-11-08 | 이 프로젝트의 AWS 배포는 어떤 구성 요소로 되어 있고, 코드가 서버까지 어떻게 가는가 | [qna/2026-11-08-aws-deploy-structure-and-flow.md](qna/2026-11-08-aws-deploy-structure-and-flow.md) |
| 2026-11-08 | k6는 무엇이고, 추첨 시나리오에서 어떻게 쓰는가 | [qna/2026-11-08-what-is-k6.md](qna/2026-11-08-what-is-k6.md) |
| 2026-11-08 (11/9 갱신) | 부하 발생기 EC2는 무엇이고, 지금 떠 있는 AWS 구성에 어떻게 더해 쓰는가 | [qna/2026-11-08-load-generator-ec2-setup.md](qna/2026-11-08-load-generator-ec2-setup.md) |
| 2026-11-06 | 떠 있는 EC2를 셸 명령으로 2시간 뒤에 자동으로 내릴 수 있는가 | [qna/2026-11-06-ec2-scheduled-shutdown.md](qna/2026-11-06-ec2-scheduled-shutdown.md) |
| 2026-11-06 | 부하 측정에서 로컬과 배포 서버를 어떻게 나눠 쓰는가 | [qna/2026-11-06-local-vs-deploy-load-comparison.md](qna/2026-11-06-local-vs-deploy-load-comparison.md) |
| 2026-11-05 | 이탈이나 결제 완료 뒤 줄을 다시 서게 하려면 입장 토큰을 어떻게 폐기할 수 있는가 | [qna/2026-11-05-entry-token-revocation-options.md](qna/2026-11-05-entry-token-revocation-options.md) |
| 2026-11-05 | 추첨에서 관측하고 측정해야 할 요소는 무엇인가 | [qna/2026-11-05-lottery-observability-measurement.md](qna/2026-11-05-lottery-observability-measurement.md) |
| 2026-11-05 | 배포 서버에 접속해 통합 테스트와 부하 측정을 어떤 순서로 진행하는가 | [qna/2026-11-05-deploy-server-test-and-load-steps.md](qna/2026-11-05-deploy-server-test-and-load-steps.md) |
| 2026-11-05 | 추첨의 application과 domain 패키지는 어떤 기준으로 나눌 수 있는가 | [qna/2026-11-05-lottery-package-structure.md](qna/2026-11-05-lottery-package-structure.md) |
| 2026-11-05 | IntelliJ의 @Transactional self-invocation 경고는 무슨 뜻인가 | [qna/2026-11-05-transactional-self-invocation.md](qna/2026-11-05-transactional-self-invocation.md) |
| 2026-11-05 | Optional 분기를 if로 쓸 때와 map·orElseGet으로 쓸 때 무엇이 다른가 | [qna/2026-11-05-optional-if-vs-functional.md](qna/2026-11-05-optional-if-vs-functional.md) |
| 2026-11-05 | m7i-flex.xlarge를 잠깐 띄워 통합·부하 테스트를 하면 비용이 걱정할 수준인가 | [qna/2026-11-05-load-test-ec2-cost.md](qna/2026-11-05-load-test-ec2-cost.md) |
| 2026-11-04 | Java의 var는 자료형인가 | [qna/2026-11-04-java-var.md](qna/2026-11-04-java-var.md) |
| 2026-11-04 | @ConditionalOnProperty의 havingValue와 matchIfMissing은 무엇을 뜻하는가 | [qna/2026-11-04-conditional-on-property.md](qna/2026-11-04-conditional-on-property.md) |
| 2026-11-04 | 배포 서버 접속에 왜 SSH 공개 키와 내 IP를 모두 알려 줘야 하는가 | [qna/2026-11-04-ssh-key-and-ip-allowlist.md](qna/2026-11-04-ssh-key-and-ip-allowlist.md) |
| 2026-11-04 | 구현은 메인 에이전트가 하고 코드 리뷰는 서브에이전트에 맡기면 무엇이 좋은가 | [qna/2026-11-04-main-implement-subagent-review.md](qna/2026-11-04-main-implement-subagent-review.md) |
| 2026-11-04 | UPDATE ... IN (SELECT ... LIMIT n FOR UPDATE)가 n개보다 많이 바꿀 때 고치는 세 가지 방법은 어떻게 다른가 | [qna/2026-11-04-update-limit-for-update-rescan.md](qna/2026-11-04-update-limit-for-update-rescan.md) |
| 2026-11-03 | 입구 어댑터를 presentation에 두는 4계층 구조와 패키지 세팅(B안 이동 대상) | [qna/2026-11-03-four-layer-package-setup.md](qna/2026-11-03-four-layer-package-setup.md) |
| 2026-11-03 | 소비자·스케줄러를 presentation으로 옮기면 헥사고날로 가는 것인가, 4계층의 한계인가 | [qna/2026-11-03-presentation-move-is-not-hexagonal.md](qna/2026-11-03-presentation-move-is-not-hexagonal.md) |
| 2026-11-03 | 소비자·스케줄러 같은 입구는 entity인가 entry인가(인바운드·아웃바운드 어댑터) | [qna/2026-11-03-inbound-outbound-adapter.md](qna/2026-11-03-inbound-outbound-adapter.md) |
| 2026-11-02 | 예매(reservation-service)는 outbox를 언제 어떻게 쓰는가 | [qna/2026-11-02-reservation-outbox-usage.md](qna/2026-11-02-reservation-outbox-usage.md) |
| 2026-11-02 | 11/2 추첨 MVP와 Gateway 입장 토큰 작업을 이슈·PR 단위로 다시 읽기(코드 리뷰와 개념 정리) | [qna/2026-11-02-lottery-mvp-code-review-report.md](qna/2026-11-02-lottery-mvp-code-review-report.md) |
| 2026-11-02 | 클라우드 배포는 왜 하고, EC2·AWS 가입·요금은 어떻게 다루며, 우리 프로젝트에는 어느 정도 규모가 맞나 | [qna/2026-11-02-aws-ec2-deployment-basics.md](qna/2026-11-02-aws-ec2-deployment-basics.md) |
| 2026-11-01 | .env를 지우고 다시 만들면 DB 비밀번호는 어떻게 되고, 로컬 데이터가 필요 없으면 괜찮은가 | [qna/2026-11-01-regenerate-env-db-password.md](qna/2026-11-01-regenerate-env-db-password.md) |
| 2026-11-01 | CI 실패는 무엇이고, PR의 CI 자동 수정은 무엇을 하나 | [qna/2026-11-01-ci-failure-and-auto-fix.md](qna/2026-11-01-ci-failure-and-auto-fix.md) |
| 2026-11-01 | 추첨에서 매수 때문에 건너뛴 2매 응모자는 예비자가 되나, 탈락하나 | [qna/2026-11-01-lottery-skipped-entrant-waitlist.md](qna/2026-11-01-lottery-skipped-entrant-waitlist.md) |
| 2026-10-31 | 앞 PR을 스쿼시 머지한 뒤 그 위에 쌓은 브랜치는 왜 강제 푸시가 필요하고, 다른 방법은 무엇인가 | [qna/2026-10-31-stacked-branch-after-squash.md](qna/2026-10-31-stacked-branch-after-squash.md) |
| 2026-10-31 | 세션을 Redis에 공유하는 구조에서 Redis가 죽으면 전체가 멈추는데, 어떻게 막을 수 있나 | [qna/2026-10-31-session-redis-single-point-of-failure.md](qna/2026-10-31-session-redis-single-point-of-failure.md) |
| 2026-10-31 | 서비스 간 인증에서 mTLS와 서비스 메시는 무엇이고, 이 프로젝트에 왜 과한가 | [qna/2026-10-31-mtls-service-mesh.md](qna/2026-10-31-mtls-service-mesh.md) |
| 2026-10-30 | NOT_SENT는 언제 생기고, 추첨 자동결제의 NOT_SENT와 결과 미수신 만료는 어떤 선택지로 처리할 수 있나 | [qna/2026-10-30-lottery-payment-failure-options.md](qna/2026-10-30-lottery-payment-failure-options.md) |
| 2026-10-30 | 추첨을 별도 서비스로 분리하면 무엇이 문제가 되고, 지금 무엇을 해 두면 되는가 | [qna/2026-10-30-lottery-service-split-readiness.md](qna/2026-10-30-lottery-service-split-readiness.md) |
| 2026-10-30 | 추첨 자동결제 전용 Bulkhead의 동시 3, 전용 토픽, 백프레셔, 그룹별 Circuit Breaker는 무엇인가 | [qna/2026-10-30-lottery-billing-bulkhead.md](qna/2026-10-30-lottery-billing-bulkhead.md) |
| 2026-10-30 | 추첨을 한 트랜잭션으로 실행하면 무엇이 보장되고 무엇이 위험하며, 청크·중간 상태는 무엇이고 긴 트랜잭션은 어떻게 미리 막는가 | [qna/2026-10-30-lottery-draw-transaction-unit.md](qna/2026-10-30-lottery-draw-transaction-unit.md) |
| 2026-10-30 | 추첨 응모에서 1매·2매를 다른 서비스는 어떻게 처리하고, 1인 1매만 받으면 무엇이 달라지는가 | [qna/2026-10-30-lottery-entry-quantity.md](qna/2026-10-30-lottery-entry-quantity.md) |
| 2026-10-30 | 실제 운영 중인 추첨 서비스는 공정성을 어떻게 보여 주는가 | [qna/2026-10-30-lottery-fairness-in-real-services.md](qna/2026-10-30-lottery-fairness-in-real-services.md) |
| 2026-10-30 | 응모 취소 뒤 재응모를 새 행으로 만들면 한 사용자에게 행이 여러 개 생기는데, 무엇이 불리한가 | [qna/2026-10-30-lottery-cancel-multiple-rows.md](qna/2026-10-30-lottery-cancel-multiple-rows.md) |
| 2026-10-29 | 추첨석을 등급으로 구분하는 것과 추첨이 좌석을 먼저 선점해 두는 것은 무엇이 다른가 | [qna/2026-10-29-lottery-seat-allocation-vs-hold.md](qna/2026-10-29-lottery-seat-allocation-vs-hold.md) |
| 2026-10-29 | 추첨 응모 때 빌링키 유무는 누가 확인하는 것이 가장 좋은가 | [qna/2026-10-29-lottery-billing-key-check-location.md](qna/2026-10-29-lottery-billing-key-check-location.md) |
| 2026-10-29 | 스케줄러와 워커란 무엇이고, 추첨 설계에서 왜 5개나 필요한가 | [qna/2026-10-29-what-is-scheduler.md](qna/2026-10-29-what-is-scheduler.md) |
| 2026-10-29 | 입장 토큰을 Gateway, Reservation, 양쪽 중 어디서 검증할까 | [qna/2026-10-29-entry-token-verification-location.md](qna/2026-10-29-entry-token-verification-location.md) |
| 2026-10-29 | ISE-20 입장 토큰 계약은 무엇을 기준으로 범위를 정했고, 누가 함께 정해야 하나 | [qna/2026-10-29-ise20-entry-token-contract-scope.md](qna/2026-10-29-ise20-entry-token-contract-scope.md) |
| 2026-10-29 | 서버 문제로 실패한 추첨 자동결제를 끝까지 보장하면 어떤 비용이 드나 | [qna/2026-10-29-lottery-retry-guarantee-cost.md](qna/2026-10-29-lottery-retry-guarantee-cost.md) |
| 2026-10-29 | 추첨 응모 상태의 WON → WON은 어떤 상황인가 | [qna/2026-10-29-lottery-won-to-won-retry.md](qna/2026-10-29-lottery-won-to-won-retry.md) |
| 2026-10-29 | 대기열 폴링 간격의 jitter는 무엇이고, 서버와 프론트 중 어디서 넣어야 하나 | [qna/2026-10-29-queue-polling-jitter.md](qna/2026-10-29-queue-polling-jitter.md) |
| 2026-10-29 | 대기열 응답 상태에 READY를 더하는 제안(ISE-68)을 어떻게 볼까 | [qna/2026-10-29-queue-ready-status-review.md](qna/2026-10-29-queue-ready-status-review.md) |
| 2026-10-29 | 선점(주문 완료) 응답에 좌석 ID를 넣어야 하나 | [qna/2026-10-29-seat-hold-response-seat-ids.md](qna/2026-10-29-seat-hold-response-seat-ids.md) |
| 2026-10-29 | 우리 입장 토큰은 어떻게 구성되어 있나 | [qna/2026-10-29-entry-token-structure.md](qna/2026-10-29-entry-token-structure.md) |
| 2026-10-29 | 빌링키란 무엇이고 추첨 자동결제에 왜 쓰나 | [qna/2026-10-29-what-is-billing-key.md](qna/2026-10-29-what-is-billing-key.md) |
| 2026-10-29 | 추첨 자동결제를 위해 결제 담당과 무엇을 합의해야 하나 | [qna/2026-10-29-lottery-payment-agreement-items.md](qna/2026-10-29-lottery-payment-agreement-items.md) |
| 2026-10-28 | 회차당 매수를 4매에서 1인 1매로 바꾸면 무엇을 얻고 무엇을 치르나 | [qna/2026-10-28-one-ticket-per-user-cost.md](qna/2026-10-28-one-ticket-per-user-cost.md) |
| 2026-10-28 | 대기열 진입부터 입장 토큰 발급까지 요청·Redis·상태 전이를 단계별로 보면 | [qna/2026-10-28-queue-entry-to-admission-sequence.md](qna/2026-10-28-queue-entry-to-admission-sequence.md) |
| 2026-10-28 | 이미 입장 토큰을 받은 사용자가 대기열 진입(POST)을 다시 부르면 토큰을 누가 돌려줘야 하나 | [qna/2026-10-28-queue-entry-vs-status-responsibility.md](qna/2026-10-28-queue-entry-vs-status-responsibility.md) |
| 2026-10-28 | ISE-47 user-service 계층 정리는 무엇을 왜 바꿨고 어떻게 검증했나 | [qna/2026-10-28-ise47-user-layer-refactoring.md](qna/2026-10-28-ise47-user-layer-refactoring.md) |
| 2026-10-28 | 우리 4계층 분리는 DDD인가, 계층형 아키텍처와 DDD는 무엇이 다른가 | [qna/2026-10-28-layered-architecture-vs-ddd.md](qna/2026-10-28-layered-architecture-vs-ddd.md) |
| 2026-10-28 | AI 검증 #7의 "응답 레코드 분리 거부"는 무엇을 거부한 것인가 | [qna/2026-10-28-response-record-separation.md](qna/2026-10-28-response-record-separation.md) |
| 2026-10-27 | 추첨 배분 설계로 이 프로젝트에서 무엇을 얻어 갈 수 있나 | [qna/2026-10-27-lottery-design-learning-points.md](qna/2026-10-27-lottery-design-learning-points.md) |
| 2026-10-27 | 마일스톤을 더 잘게 나눠야 하나, 공통 모듈은 필요할 때 늘려도 되나 | [qna/2026-10-27-milestone-granularity-and-common-module-timing.md](qna/2026-10-27-milestone-granularity-and-common-module-timing.md) |
| 2026-10-27 | .env 파일과 initLocalEnv는 무엇이고, 통합 뒤 왜 다시 만들어야 하나 | [qna/2026-10-27-env-file-and-init-local-env.md](qna/2026-10-27-env-file-and-init-local-env.md) |
| 2026-10-27 | 공통 모듈 4개와 config-repo 파일 14개는 너무 잘게 나눈 것인가 | [qna/2026-10-27-common-module-config-repo-granularity.md](qna/2026-10-27-common-module-config-repo-granularity.md) |
| 2026-10-27 | Config 서버는 무슨 역할을 하고, 설정을 따로 분리하면 무엇을 얻고 잃나 | [qna/2026-10-27-config-server-role-tradeoffs.md](qna/2026-10-27-config-server-role-tradeoffs.md) |
| 2026-10-27 | 브랜치 이름에 슬래시(/)를 여러 번 넣는 것은 흔한가, 주의할 점은 무엇인가 | [qna/2026-10-27-branch-name-slashes.md](qna/2026-10-27-branch-name-slashes.md) |
| 2026-10-27 | DSL(도메인 특화 언어)은 무엇이고 Gradle의 Kotlin·Groovy DSL은 어떤 의미인가 | [qna/2026-10-27-what-is-dsl.md](qna/2026-10-27-what-is-dsl.md) |
| 2026-10-27 | Gradle 빌드 스크립트를 Kotlin DSL에서 Groovy로 바꾸면 무엇이 달라지나 | [qna/2026-10-27-gradle-groovy-vs-kotlin-dsl.md](qna/2026-10-27-gradle-groovy-vs-kotlin-dsl.md) |
| 2026-10-27 | Spring Initializr로 만들었으면 Gradle 연결과 초기 구성이 알아서 됐을까 | [qna/2026-10-27-spring-initializr-vs-manual-multimodule.md](qna/2026-10-27-spring-initializr-vs-manual-multimodule.md) |
| 2026-10-27 | IntelliJ에서 "Gradle 프로젝트로 불러온다"는 것은 무엇이고 왜 필요한가 | [qna/2026-10-27-intellij-gradle-project.md](qna/2026-10-27-intellij-gradle-project.md) |
| 2026-10-27 | Spring 설정 파일은 application.properties와 application.yml 중 무엇을 쓰고, 차이는 무엇인가 | [qna/2026-10-27-properties-vs-yaml.md](qna/2026-10-27-properties-vs-yaml.md) |
| 2026-10-27 | 이 프로젝트의 4계층(표현·응용·도메인·인프라)은 각각 무엇을 맡고 어떻게 의존하나 | [qna/2026-10-27-four-layer-architecture.md](qna/2026-10-27-four-layer-architecture.md) |
| 2026-10-27 | 초기 개발 환경(M1 뼈대)은 어떻게 구성했고 각 선택의 이유는 무엇인가 | [qna/2026-10-27-initial-environment-setup.md](qna/2026-10-27-initial-environment-setup.md) |
| 2026-10-26 | 좌석 선점 API의 v1·v2는 무엇이고, 응답을 가공 없이 바로 쓰게 하려면 어떻게 하나 | [qna/2026-10-26-seat-hold-v1-v2-response.md](qna/2026-10-26-seat-hold-v1-v2-response.md) |

## 하루 정리

| 날짜 | 정리 |
|---|---|
| 2026-11-10 | [daily/2026-11-10.md](daily/2026-11-10.md) |
