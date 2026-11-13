# junhyun 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`, 작업 범위 보고서(PDF)는 `reports/YYYY-MM-DD-<slug>.pdf`에 둔다. 최신이 위로 온다.

## 하루 정리

| 날짜 | 정리 |
|---|---|
| 2026-11-03 | [결제 운영 신뢰성을 끝낸 날: Outbox 재시도·DLQ·보관 정리, 지표와 알림, 감사되는 Ops 명령, 장애 시나리오 테스트](daily/2026-11-03.md) |
| 2026-11-02 | [프론트를 세운 날: 서버 시각 보정, 토큰 보관과 재발급, Query 캐시, 빌드 상수 접기, 접근성, 되돌림 검사](daily/2026-11-02.md) |
| 2026-10-23 | [프로젝트 첫날: SA 1차 범위, 서비스 경계, 교차 검토, 문서화 체계](daily/2026-10-23.md) |

## 보고서

| 날짜 | 보고서 | 범위 |
|---|---|---|
| 2026-11-03 | [결제 운영 신뢰성 작업 보고서 (PDF, 15쪽)](reports/2026-11-03-payment-ops-reliability.pdf) | ISE-40·41·42·44, PR 12개. 같은 날 [하루 정리](daily/2026-11-03.md)의 내용을 구조 그림 2장, 장애 시나리오 결과 표, 면접 답변으로 다시 묶었다 |

## 블로그 초안

| 날짜 | 글 | 상태 |
|---|---|---|
| 2026-10-23 | [TIL 15일차 - 티켓팅 서비스를 몇 개로 나눌까, 타임아웃은 실패가 아니다](blog/2026-10-23-til-15.html) | Tistory 임시저장 (카테고리: 백엔드 심화 TIL) |

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-11-13 | 서버 접근을 보안 그룹·사람별 키·접속 기록으로 묶은 것이 발표에서 장점이 되나(한 것, 한계, 발표에 넣는 법), 비밀번호·JWT 평문 전송을 HTTPS(IP 인증서)로 보완하는 법 | [qna/2026-11-13-server-access-security-as-strength.md](qna/2026-11-13-server-access-security-as-strength.md) |
| 2026-11-13 | 다른 조 저장소 5곳에서 배울 점과 우리 프로젝트에 적용할 것 | [qna/2026-11-13-other-teams-repos-lessons.md](qna/2026-11-13-other-teams-repos-lessons.md) |
| 2026-11-13 | Synthetic Data가 뭐고, 부하 테스트에서 왜 필요한가 | [qna/2026-11-13-what-is-synthetic-data.md](qna/2026-11-13-what-is-synthetic-data.md) |
| 2026-11-13 | 최종 발표에서 심사위원은 무엇을 보나, 우리는 무엇을 더 채워야 하나(4조 비교, AI 코드 검토 방법) | [qna/2026-11-13-final-presentation-gaps-vs-rubric.md](qna/2026-11-13-final-presentation-gaps-vs-rubric.md) |
| 2026-11-12 | 이미지 생성 API 없이 서버가 LLM(Claude API)으로 그림(SVG)을 만들 수 있나 | [qna/2026-11-12-server-side-svg-generation-with-llm.md](qna/2026-11-12-server-side-svg-generation-with-llm.md) |
| 2026-11-12 | 저장하지 않고도 내가 발급한 값인지, 그 안의 값이 바뀌지 않았는지 어떻게 아는가 | [qna/2026-11-12-signed-identifiers-stateless-verification.md](qna/2026-11-12-signed-identifiers-stateless-verification.md) |
| 2026-11-12 | 매번 전체를 다시 읽지 않고 바뀐 것만 따라 읽으려면 무엇을 기억해야 하는가 | [qna/2026-11-12-incremental-read-watermark.md](qna/2026-11-12-incremental-read-watermark.md) |
| 2026-11-12 | 대기 없는 동시 수 제한의 크기를 어떻게 정하는가 | [qna/2026-11-12-erlang-b-bulkhead-sizing.md](qna/2026-11-12-erlang-b-bulkhead-sizing.md) |
| 2026-11-12 | Kafka 소비자를 늘리면 순서는 어떻게 지켜지는가 | [qna/2026-11-12-kafka-partitions-consumer-concurrency-ordering.md](qna/2026-11-12-kafka-partitions-consumer-concurrency-ordering.md) |
| 2026-11-12 | 서명을 확인만 하는 쪽이 서명도 할 수 있으면 왜 문제이고, 어떻게 나누는가 | [qna/2026-11-12-symmetric-vs-asymmetric-signature.md](qna/2026-11-12-symmetric-vs-asymmetric-signature.md) |
| 2026-11-12 | 서비스마다 DB가 따로일 때 서로 어긋난 것을 어떻게 찾는가 | [qna/2026-11-12-reconciliation-between-services.md](qna/2026-11-12-reconciliation-between-services.md) |
| 2026-11-12 | 실패한 요청을 언제, 몇 번, 얼마나 기다렸다가 다시 보내야 하는가 | [qna/2026-11-12-retry-backoff-jitter.md](qna/2026-11-12-retry-backoff-jitter.md) |
| 2026-11-12 | 서버를 막 띄운 직후 첫 요청들은 왜 느리고 어떻게 줄이는가 | [qna/2026-11-12-jvm-warmup-cold-start.md](qna/2026-11-12-jvm-warmup-cold-start.md) |
| 2026-11-12 | 요청 횟수 제한은 어떤 방식이 있고 어디에 두는가 | [qna/2026-11-12-rate-limiting-algorithms.md](qna/2026-11-12-rate-limiting-algorithms.md) |
| 2026-11-12 | 기능 플래그의 기본값은 왜 꺼짐이어야 하나(안전한 기본값, fail-safe defaults) | [qna/2026-11-12-fail-safe-defaults.md](qna/2026-11-12-fail-safe-defaults.md) |
| 2026-11-11 | 배포 서버에서 잰 구간별 처리량(TPS)은 얼마인가 | [qna/2026-11-11-deploy-measured-throughput.md](qna/2026-11-11-deploy-measured-throughput.md) |
| 2026-11-11 | 측정 모드 알림용 슬랙 웹훅을 배포 서버에 넣는 법 | [qna/2026-11-11-loadtest-slack-webhook-on-server.md](qna/2026-11-11-loadtest-slack-webhook-on-server.md) |
| 2026-11-11 | 배포가 실패하면 팀원은 Jenkins에 어떻게 들어가서 다시 배포하나 | [qna/2026-11-11-jenkins-redeploy-after-failure.md](qna/2026-11-11-jenkins-redeploy-after-failure.md) |
| 2026-11-11 | EC2를 껐다 켜면 무엇이 바뀌고, 보안 그룹을 고쳐야 하나 | [qna/2026-11-11-ec2-stop-start-what-changes-security-group.md](qna/2026-11-11-ec2-stop-start-what-changes-security-group.md) |
| 2026-11-12 | 서비스를 다시 띄우면 왜 한동안 503이 나나 (서비스 발견의 캐시와 전파 지연) | [qna/2026-11-12-service-discovery-propagation-delay.md](qna/2026-11-12-service-discovery-propagation-delay.md) |
| 2026-11-12 | 결제 서비스를 성능·로직에서 더 발전시킬 방안 (인스턴스 사양 제외) | [qna/2026-11-12-payment-service-next-improvements.md](qna/2026-11-12-payment-service-next-improvements.md) |
| 2026-11-12 | 결제 승인 처리량을 더 올릴 수 있나 (동시 허가 수, 리틀의 법칙, 대기열 입장 속도와 맞추기) | [qna/2026-11-12-payment-throughput-improvement.md](qna/2026-11-12-payment-throughput-improvement.md) |
| 2026-11-12 | HTTPS 인증서는 반드시 외부 기관에서 받아야 하나 (자체 서명, 사설 CA, 공인 CA) | [qna/2026-11-12-https-certificate-self-signed-vs-ca.md](qna/2026-11-12-https-certificate-self-signed-vs-ca.md) |
| 2026-11-11 | 부하를 늘려 가며 결제 단계별 시간과 임계치를 재는 법 | [qna/2026-11-11-payment-stage-latency-under-ramp.md](qna/2026-11-11-payment-stage-latency-under-ramp.md) |
| 2026-11-11 | 스프링 안에서 new Thread(Runnable)로 스레드를 직접 만들어도 되나, 스레드란 무엇인가 | [qna/2026-11-11-new-thread-in-spring-and-thread-basics.md](qna/2026-11-11-new-thread-in-spring-and-thread-basics.md) |
| 2026-11-11 | 스레드 풀 크기는 CPU 바운드와 I/O 바운드에서 어떻게 다르게 잡나 | [qna/2026-11-11-thread-pool-size-cpu-vs-io-bound.md](qna/2026-11-11-thread-pool-size-cpu-vs-io-bound.md) |
| 2026-11-11 | 팀 공용 부하 발생기로 배포 서버를 재는 순서 (한 단계씩) | [qna/2026-11-11-loadgen-step-by-step.md](qna/2026-11-11-loadgen-step-by-step.md) |
| 2026-11-10 | 오픈 피크 부하 시나리오 PAY1·R1·L2는 각각 무엇을 재는가 | [qna/2026-11-10-open-peak-payment-scenarios.md](qna/2026-11-10-open-peak-payment-scenarios.md) |
| 2026-11-09 | 배포 서버에 부하 측정을 하면 AWS 요금이 더 나오나 | [qna/2026-11-09-load-test-aws-cost.md](qna/2026-11-09-load-test-aws-cost.md) |
| 2026-11-09 | 부하 발생기 EC2는 얼마나 커야 하나 (c6i.large와 c6i.xlarge) | [qna/2026-11-09-loadgen-instance-size.md](qna/2026-11-09-loadgen-instance-size.md) |
| 2026-11-09 | Anthropic API 키는 어디에 넣고, 배포 때도 따라가나 | [qna/2026-11-09-anthropic-key-local-and-deploy.md](qna/2026-11-09-anthropic-key-local-and-deploy.md) |
| 2026-11-09 | Spring AI는 어떤 프레임워크이고, 쓰면 무엇이 좋은가 (우리 챗봇과 비교) | [qna/2026-11-09-spring-ai-what-and-why.md](qna/2026-11-09-spring-ai-what-and-why.md) |
| 2026-11-09 | 예매 문의 챗봇(ISE-126)은 어떻게 구현돼 있나, 왜 별도 모듈인가 | [qna/2026-11-09-assistant-chatbot-structure.md](qna/2026-11-09-assistant-chatbot-structure.md) |
| 2026-11-09 | ISE-169 결제 TX1 커밋 응답 유실은 무엇이고, 왜 "재시도가 이어받기" 원안으로는 풀리지 않나 | [qna/2026-11-09-tx1-commit-response-loss.md](qna/2026-11-09-tx1-commit-response-loss.md) |
| 2026-11-08 | 실제 티켓 서비스는 매크로와 직접 API 호출을 어떻게 막나 | [qna/2026-11-08-production-ticketing-anti-bot-layers.md](qna/2026-11-08-production-ticketing-anti-bot-layers.md) |
| 2026-11-08 | 프론트를 거치는 사람은 API를 직접 쏘는 사람보다 대기열에 늦게 서지 않나 | [qna/2026-11-08-queue-fairness-frontend-vs-direct-api.md](qna/2026-11-08-queue-fairness-frontend-vs-direct-api.md) |
| 2026-11-08 | 오픈 시각이 되면 새로고침 없이 [예매하기]가 켜지는 것을 어떻게 구현했나 | [qna/2026-11-08-open-countdown-button-without-refresh.md](qna/2026-11-08-open-countdown-button-without-refresh.md) |
| 2026-11-08 | 접수형(async) 좌석 선점은 무엇이고, 왜 하며, 지금 어디까지 왔나 | [qna/2026-11-08-async-seat-hold-what-and-why.md](qna/2026-11-08-async-seat-hold-what-and-why.md) |
| 2026-11-05 | EC2에 직접 올리는 것 말고, HTTPS까지 알아서 붙여 주는 AWS 서비스(Lightsail 등)는 무엇이 있나 | [qna/2026-11-05-aws-managed-https-options.md](qna/2026-11-05-aws-managed-https-options.md) |
| 2026-11-05 | 외부에서 IP로 프론트가 안 열린다는데 왜이고, HTTPS는 무료로 달 수 있나 | [qna/2026-11-05-why-front-unreachable-by-ip.md](qna/2026-11-05-why-front-unreachable-by-ip.md) |
| 2026-11-05 | 주소에 ?demo=on을 붙이면 보이는 요청 로그 패널은 어떻게 쓰나 | [qna/2026-11-05-demo-mode-request-log-panel.md](qna/2026-11-05-demo-mode-request-log-panel.md) |
| 2026-11-05 | 팀원에게 받은 공개 키와 IP로 배포 서버 SSH 접속을 열어 주는 방법 | [qna/2026-11-05-team-ssh-access-to-deploy-server.md](qna/2026-11-05-team-ssh-access-to-deploy-server.md) |
| 2026-11-05 | 팀원이 가비지 컬렉터를 Serial에서 G1으로 바꿔 잰 내용은 무엇이고, 배포 서버에는 어떤 의미인가 | [qna/2026-11-05-gc-serial-to-g1-seathold-measurement.md](qna/2026-11-05-gc-serial-to-g1-seathold-measurement.md) |
| 2026-11-05 | 부하 테스트를 로컬(노트북)에서 배포 서버로 쏘면 안 되나 | [qna/2026-11-05-load-test-from-local-machine.md](qna/2026-11-05-load-test-from-local-machine.md) |
| 2026-11-05 | 부하 테스트(k6)는 어디에서 돌리고, k6도 컨테이너로 띄우나 | [qna/2026-11-05-where-to-run-k6-load-generator.md](qna/2026-11-05-where-to-run-k6-load-generator.md) |
| 2026-11-05 | 배포 서버에 컨테이너가 왜 20개나 뜨나 | [qna/2026-11-05-why-20-containers-in-deploy.md](qna/2026-11-05-why-20-containers-in-deploy.md) |
| 2026-11-05 | 부하 테스트 때문에 EC2를 flex 인스턴스에서 일반 인스턴스로 바꾸려면 어떻게 하나 | [qna/2026-11-05-ec2-change-instance-type-flex-to-standard.md](qna/2026-11-05-ec2-change-instance-type-flex-to-standard.md) |
| 2026-11-05 | 프론트 테스트의 "8×8 동시 실행"은 무엇이고 왜 메모리를 다 쓰나 | [qna/2026-11-05-vitest-8x8-concurrency-memory.md](qna/2026-11-05-vitest-8x8-concurrency-memory.md) |
| 2026-11-04 | Kafka는 무엇이고 어떻게 동작하며, 이 프로젝트에서는 어떻게 쓰는가 | [qna/2026-11-04-kafka-basics.md](qna/2026-11-04-kafka-basics.md) |
| 2026-11-04 | DLQ(Dead Letter Queue)는 무엇이고 이 프로젝트에서는 어떻게 쓰는가 (2026-10-26 노트 보강) | [qna/2026-10-26-dlq-dead-letter-queue.md](qna/2026-10-26-dlq-dead-letter-queue.md) |
| 2026-11-04 | 대기열 진입 API는 멱등한가, Lua 스크립트가 멱등을 만들어 주는가 | [qna/2026-11-04-queue-enter-idempotency-lua.md](qna/2026-11-04-queue-enter-idempotency-lua.md) |
| 2026-11-04 | 배포 비밀값을 Jenkins Credentials나 GitHub Secrets에 두고 관리하는 방법 | [qna/2026-11-04-deploy-secrets-jenkins-github.md](qna/2026-11-04-deploy-secrets-jenkins-github.md) |
| 2026-11-04 | AWS 지출 한도(spend limit)에 걸려 프로젝트가 멈추면 어떻게 하나 | [qna/2026-11-04-aws-spend-limit-pause.md](qna/2026-11-04-aws-spend-limit-pause.md) |
| 2026-11-04 | 배포 서버의 관리 화면(Grafana 등)을 IP 주소로 바로 열 수 있나 | [qna/2026-11-04-admin-ui-access-by-ip.md](qna/2026-11-04-admin-ui-access-by-ip.md) |
| 2026-11-04 | EC2에 탄력적 IP(Elastic IP)는 어떻게 할당하나 | [qna/2026-11-04-ec2-elastic-ip-allocate.md](qna/2026-11-04-ec2-elastic-ip-allocate.md) |
| 2026-11-04 | 배포 EC2 요금은 얼마이고, DB까지 한 인스턴스에 띄우는 설계인가 | [qna/2026-11-04-deploy-cost-and-single-host-db.md](qna/2026-11-04-deploy-cost-and-single-host-db.md) |
| 2026-11-04 | GHCR(GitHub Container Registry)은 무엇이고 왜 쓰나 | [qna/2026-11-04-what-is-ghcr.md](qna/2026-11-04-what-is-ghcr.md) |
| 2026-11-04 | Grafana Alloy는 무엇이고 왜 쓰나 | [qna/2026-11-04-grafana-alloy-what-is.md](qna/2026-11-04-grafana-alloy-what-is.md) |
| 2026-11-04 | GitHub Actions로 배포 이미지를 만들어 GHCR에 올리는 워크플로는 어떻게 읽나 | [qna/2026-11-04-github-actions-ghcr-image-workflow.md](qna/2026-11-04-github-actions-ghcr-image-workflow.md) |
| 2026-11-04 | 배포용 AWS 계정을 만든 뒤 무엇을 어떤 순서로 설정하나 | [qna/2026-11-04-aws-account-initial-setup.md](qna/2026-11-04-aws-account-initial-setup.md) |
| 2026-11-04 | 배포·알림용 슬랙 채널을 만든 뒤 무엇을 설정해야 하나 | [qna/2026-11-04-slack-incoming-webhook-setup.md](qna/2026-11-04-slack-incoming-webhook-setup.md) |
| 2026-11-02 | 실제 PG(토스페이먼츠)를 붙여도 Mock PG가 필요한가 | [qna/2026-11-02-mock-pg-with-real-pg.md](qna/2026-11-02-mock-pg-with-real-pg.md) |
| 2026-11-02 | 토스페이먼츠 테스트 연동을 위해 미리 준비해 둘 것은 무엇인가 | [qna/2026-11-02-toss-payments-test-setup.md](qna/2026-11-02-toss-payments-test-setup.md) |
| 2026-11-02 | Gateway는 왜 만료된 Access 토큰에 TOKEN_EXPIRED가 아니라 UNAUTHORIZED를 주는가 (보안상 숨긴 것인가) | [qna/2026-11-02-gateway-expired-token-error-code.md](qna/2026-11-02-gateway-expired-token-error-code.md) |
| 2026-11-02 | GitHub Actions 워크플로를 특정 폴더가 바뀔 때만 돌리면 필수 검사와 어떻게 부딪히나 | [qna/2026-11-02-required-checks-and-paths-filter.md](qna/2026-11-02-required-checks-and-paths-filter.md) |
| 2026-11-02 | 앞단에 가상 대기실을 두면 서버에 만든 대기열 설계는 의미가 없어지는가 | [qna/2026-11-02-waiting-room-vs-server-queue.md](qna/2026-11-02-waiting-room-vs-server-queue.md) |
| 2026-11-02 | SPA와 API를 잇는 리버스 프록시 중계와 BFF는 무엇이 다른가 (실제 티켓팅 서비스라면 어느 쪽인가) | [qna/2026-11-02-reverse-proxy-vs-bff.md](qna/2026-11-02-reverse-proxy-vs-bff.md) |
| 2026-11-02 | React SPA로 정한 뒤 함께 쓸 도구는 무엇으로 고르나 (티켓팅 프론트 사례) | [qna/2026-11-02-react-spa-stack.md](qna/2026-11-02-react-spa-stack.md) |
| 2026-11-02 | 백엔드 MSA 프로젝트의 프론트를 같은 저장소에 둘지, Spring Boot 안에 넣을지 | [qna/2026-11-02-frontend-repo-and-stack.md](qna/2026-11-02-frontend-repo-and-stack.md) |
| 2026-10-29 | 결제 서비스 구현 완료는 M3까지인가 M4까지인가 | [qna/2026-10-29-payment-done-scope.md](qna/2026-10-29-payment-done-scope.md) |
| 2026-10-29 | Redis에서 두 곳을 지울 때 순서가 왜 중요한가 (대기열 만료 정리 사례) | [qna/2026-10-29-redis-write-order-recovery.md](qna/2026-10-29-redis-write-order-recovery.md) |
| 2026-10-29 | 폴링 간격에 ±20% jitter를 섞는다는 것은 무슨 뜻인가 | [qna/2026-10-29-polling-interval-jitter.md](qna/2026-10-29-polling-interval-jitter.md) |
| 2026-10-28 | 도메인은 예외 종류만 알고 상태 코드 매핑은 바깥 계층이 맡는 전략인가 | [qna/2026-10-28-domain-exception-http-mapping.md](qna/2026-10-28-domain-exception-http-mapping.md) |
| 2026-10-28 | Outbox·처리 이력 같은 공통 메시징 코드를 common에 둘지, 별도 모듈로 나눌지 | [qna/2026-10-28-common-messaging-placement.md](qna/2026-10-28-common-messaging-placement.md) |
| 2026-10-27 | common 모듈은 왜 여러 개로 나뉘었고 Docker에서도 컨테이너가 여러 개인가 | [qna/2026-10-27-common-module-split-and-containers.md](qna/2026-10-27-common-module-split-and-containers.md) |
| 2026-10-27 | Spring Boot 프로젝트의 개발·검증 스크립트는 왜 Python으로 작성했는가 | [qna/2026-10-27-spring-boot-python-scripts.md](qna/2026-10-27-spring-boot-python-scripts.md) |
| 2026-10-27 | 현재 구현에서 어떤 부분을 먼저 보강하면 좋은가 | [qna/2026-10-27-implementation-hardening-review.md](qna/2026-10-27-implementation-hardening-review.md) |
| 2026-10-27 | 가독성과 학습을 위해 공통 코드 작성 규칙을 어떻게 보완하면 좋은가 | [qna/2026-10-27-common-coding-rules.md](qna/2026-10-27-common-coding-rules.md) |
| 2026-10-27 | 서비스가 Access JWT를 직접 검증할 때 HS256과 RS256은 무엇이 다른가 | [qna/2026-10-27-jwt-hs256-vs-rs256.md](qna/2026-10-27-jwt-hs256-vs-rs256.md) |
| 2026-10-27 | MSA 사용자 JWT·Gateway 내부 토큰 비교와 이중 검증의 비용·보안 절충 | [qna/2026-10-27-msa-service-identity-verification.md](qna/2026-10-27-msa-service-identity-verification.md) |
| 2026-10-26 | fail open과 fail closed는 무엇이고 어떻게 고르는가 | [qna/2026-10-26-fail-open-fail-closed.md](qna/2026-10-26-fail-open-fail-closed.md) |
| 2026-10-26 | Redis Lua 스크립트는 무엇이고 왜 쓰는가 | [qna/2026-10-26-redis-lua-script.md](qna/2026-10-26-redis-lua-script.md) |
| 2026-10-23 | M1(Kafka 비동기 접수 설계)을 자세히 설명해 줘 | [sa-review/03-seat-hold-kafka-design.md](../../sa-review/03-seat-hold-kafka-design.md) |
| 2026-10-23 | 원래 PG사의 타임아웃은 어떻게 처리돼? | [sa-review/02-pg-timeout-handling.md](../../sa-review/02-pg-timeout-handling.md) |
| 2026-10-23 | 알림을 넣으면 새 서비스가 생기나, 범위는 어떻게 넓히나 | [qna/2026-10-23-notification-service-scope.md](qna/2026-10-23-notification-service-scope.md) |
| 2026-10-23 | 대기열에 RabbitMQ를 쓰면 안 되나, RabbitMQ는 필수인가 | [qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md](qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md) |
| 2026-10-23 | 발제 추천을 무시하면 서비스를 몇 개로? 좌석·예매를 한 서비스에 두는 이유, Queue 분리 이점 | [qna/2026-10-23-ticketing-service-boundaries.md](qna/2026-10-23-ticketing-service-boundaries.md) |

PG 타임아웃과 M1 노트는 이 체계를 만들기 전에 SA 검토 문서와 함께 `docs/sa-review/`에 저장했다. 팀 설계 검토와 이어지는 내용이라 위치는 옮기지 않고 링크만 둔다.
