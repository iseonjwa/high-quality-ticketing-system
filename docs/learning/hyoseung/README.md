# hyoseung 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-11-14 | 결제 거절 뒤 보상(좌석 원복)은 어떤 흐름이고 ISE-267 캡처는 무엇을 보여 주는가 | [qna/2026-11-14-saga-payment-declined-compensation.md](qna/2026-11-14-saga-payment-declined-compensation.md) |
| 2026-11-13 | 내 컴퓨터 IP는 왜 장소마다 바뀌고, 카페에서 배포 서버 SSH가 막히는가 | [qna/2026-11-13-public-private-ip-and-security-group.md](qna/2026-11-13-public-private-ip-and-security-group.md) |
| 2026-11-13 | Prometheus와 Grafana는 무엇이고 부하 측정에서 어떻게 쓰는가 | [qna/2026-11-13-prometheus-grafana-role.md](qna/2026-11-13-prometheus-grafana-role.md) |
| 2026-11-13 | P4b 상태 쿼리에서 둘 다 15,000행을 읽는데 권우님 방식이 왜 가벼운가 | [qna/2026-11-13-p4b-state-query-columns.md](qna/2026-11-13-p4b-state-query-columns.md) |
| 2026-11-09 | 도커 이미지는 정확히 무엇이고, dev 롤백 없이 개선 전·1차·2차를 배포 서버에서 재는 방법 | [qna/2026-11-09-docker-image-per-sha-stage-measurement.md](qna/2026-11-09-docker-image-per-sha-stage-measurement.md) |
| 2026-11-08 | 부하를 주는 쪽(k6)과 받는 쪽(서비스)이 같은 노트북이면 무엇이 문제이고, 병목을 어떻게 가리나 | [qna/2026-11-08-load-generator-vs-service-bottleneck.md](qna/2026-11-08-load-generator-vs-service-bottleneck.md) |
| 2026-11-08 | 추첨 설정이 좌석 등급을 바꾼다는 뜻, P4b 캐시를 Redis에 둘지 서비스 메모리에 둘지 | [qna/2026-11-08-p4b-cache-lottery-grade-redis.md](qna/2026-11-08-p4b-cache-lottery-grade-redis.md) |
| 2026-11-08 | 좌석 조회 성능 개선(ISE-189 P4a) 포트폴리오 요약, GC가 할 일이 없어졌다는 뜻 | [qna/2026-11-08-seat-query-p4a-portfolio.md](qna/2026-11-08-seat-query-p4a-portfolio.md) |
| 2026-11-08 | CPU를 나눠 쓰면 왜 요청이 느려지나, 800%는 코어 8개를 준 것인가 | [qna/2026-11-08-cpu-time-sharing-why-slower.md](qna/2026-11-08-cpu-time-sharing-why-slower.md) |
| 2026-11-07 | Eden·Survivor·Age는 어떻게 움직이고, RATE=50에서 힙은 정말 꽉 찼나, OOM은 언제 나나 | [qna/2026-11-07-young-gc-survivor-oom.md](qna/2026-11-07-young-gc-survivor-oom.md) |
| 2026-11-07 | 잘하고 싶은데 무엇부터 공부해야 하나 | [qna/2026-11-07-what-to-study-first.md](qna/2026-11-07-what-to-study-first.md) |
| 2026-11-06 | 좌석 조회 RATE=50에서 난 실패 3건은 무엇이고 왜 느려졌나 | [qna/2026-11-06-seat-query-rate50-gateway-timeout-gc.md](qna/2026-11-06-seat-query-rate50-gateway-timeout-gc.md) |
| 2026-11-06 | 좌석 조회는 같은 객체를 다시 쓰는가, 왜 겹치는 요청이 적은가, 하나만 재도 되나 | [qna/2026-11-06-seat-query-heap-concurrency.md](qna/2026-11-06-seat-query-heap-concurrency.md) |
| 2026-11-06 | 좌석 조회 RATE=3에서 61%가 실패했는데 왜 응답은 빨랐나 | [qna/2026-11-06-k6-fast-failures-entry-token-expired.md](qna/2026-11-06-k6-fast-failures-entry-token-expired.md) |
| 2026-11-06 | 부하 테스트는 무엇을 보려는 것이고, k6 대시보드는 무엇인가 | [qna/2026-11-06-load-test-purpose-and-dashboard.md](qna/2026-11-06-load-test-purpose-and-dashboard.md) |
| 2026-11-06 | 부하 테스트는 앞 단계까지 한 묶음으로 돌리나, 한 API만 돌리나 | [qna/2026-11-06-load-test-isolated-vs-flow.md](qna/2026-11-06-load-test-isolated-vs-flow.md) |
| 2026-11-05 | k6 첫 실행 요약표는 어떻게 읽나 | [qna/2026-11-05-k6-first-run-summary.md](qna/2026-11-05-k6-first-run-summary.md) |
| 2026-11-05 | k6는 터미널 말고 화면으로 결과를 볼 수 있나 | [qna/2026-11-05-k6-web-dashboard.md](qna/2026-11-05-k6-web-dashboard.md) |
| 2026-11-05 | 배포 서버에 SSH로 처음 들어갈 때 무슨 일이 일어났나 | [qna/2026-11-05-ssh-first-login-deploy-server.md](qna/2026-11-05-ssh-first-login-deploy-server.md) |
| 2026-11-05 | 좌석 조회 API는 부하 측정에서 왜 무너졌나 (ISE-189, #336) | [qna/2026-11-05-seat-query-collapse-under-load.md](qna/2026-11-05-seat-query-collapse-under-load.md) |
| 2026-11-04 | 회차 좌석 생성은 누가 부르고, 공연장·등급·가격은 어디서 오나 | [qna/2026-11-04-schedule-seat-creation-who-and-how.md](qna/2026-11-04-schedule-seat-creation-who-and-how.md) |
| 2026-11-04 | 내가 맡은 부분을 누구에게든 정확히 설명하려면 어떻게 공부하나 | [qna/2026-11-04-how-to-own-my-part.md](qna/2026-11-04-how-to-own-my-part.md) |
| 2026-11-04 | k6로 캐싱 개선 전·후를 어떻게 재나 | [qna/2026-11-04-k6-before-after-measurement.md](qna/2026-11-04-k6-before-after-measurement.md) |
| 2026-11-04 | 서비스의 XxxResult와 컨트롤러의 XxxResponse는 왜 따로 있나 | [qna/2026-11-04-result-vs-response-dto.md](qna/2026-11-04-result-vs-response-dto.md) |
| 2026-11-03 | 로컬 Kafka로 결제 결과 → 예매 확정 흐름을 실제로 돌려 보면 어떻게 움직이나 | [qna/2026-11-03-kafka-local-run-payment-result.md](qna/2026-11-03-kafka-local-run-payment-result.md) |
| 2026-11-03 | Kafka는 무엇이고, payment.result 소비자(ISE-73)는 무엇을 하나 | [qna/2026-11-03-kafka-basics-payment-result.md](qna/2026-11-03-kafka-basics-payment-result.md) |
| 2026-11-02 | Outbox와 processed_events는 무엇이고, PR #231(ISE-147)은 예매 흐름에 무엇을 바꾸나 | [qna/2026-11-02-outbox-pr-231.md](qna/2026-11-02-outbox-pr-231.md) |
| 2026-11-02 | 불안정한 테스트(flaky test)란 무엇이고, AdminLotteryApiTest 실패는 무엇 때문이었나 | [qna/2026-11-02-flaky-lottery-test.md](qna/2026-11-02-flaky-lottery-test.md) |
| 2026-11-02 | 회차 findById는 왜 따로 필요했나, 잠금 조회(findByIdForUpdate)와 무엇이 다른가 | [qna/2026-11-02-find-by-id-vs-for-update.md](qna/2026-11-02-find-by-id-vs-for-update.md) |
| 2026-11-02 | LEFT JOIN LATERAL은 무엇이고, 공연마다 다음 회차 하나를 붙일 때 왜 쓰나 | [qna/2026-11-02-postgresql-lateral-join.md](qna/2026-11-02-postgresql-lateral-join.md) |
| 2026-11-02 | 공연 목록 조회는 어떤 순서로 데이터를 모으고, 화면은 그 응답을 어떻게 쓰나 | [qna/2026-11-02-concert-list-flow-and-screen.md](qna/2026-11-02-concert-list-flow-and-screen.md) |
| 2026-11-02 | 추첨석(LOTTERY)은 어느 자리를 떼고, 회차 좌석 조회 화면에는 어떻게 보여 주나 | [qna/2026-11-02-lottery-seat-layout-and-display.md](qna/2026-11-02-lottery-seat-layout-and-display.md) |
| 2026-11-01 | @RequireRole은 무엇을 막나, Gateway와 어떻게 나눠 맡나, MDC는 무엇인가 | [qna/2026-11-01-require-role-and-mdc.md](qna/2026-11-01-require-role-and-mdc.md) |
| 2026-10-31 | 저장소를 왜 JpaRepository 하나로 안 만들고 인터페이스·구현으로 나눴나 | [qna/2026-10-31-repository-domain-interface-spring-data.md](qna/2026-10-31-repository-domain-interface-spring-data.md) |
| 2026-10-30 | createdAt이 created_at으로 바뀌는 건 누가 하나, 엔티티에 Lombok을 써도 되나 | [qna/2026-10-30-column-naming-lombok-entity.md](qna/2026-10-30-column-naming-lombok-entity.md) |
| 2026-10-30 | PR 머지는 squash인가 rebase인가, 우리 팀은 실제로 어떻게 하나 | [qna/2026-10-30-pr-merge-squash-or-rebase.md](qna/2026-10-30-pr-merge-squash-or-rebase.md) |
| 2026-10-30 | 콘서트 공연장 좌석은 어떻게 생겼고, 시드는 어떤 구조를 가정했나 | [qna/2026-10-30-concert-venue-seat-structure.md](qna/2026-10-30-concert-venue-seat-structure.md) |
| 2026-10-30 | 공연장 시드는 왜 필요하고, 왜 15,000석인가 | [qna/2026-10-30-venue-seed-15000-seats.md](qna/2026-10-30-venue-seed-15000-seats.md) |
| 2026-10-30 | 부모를 지우면 자식은 어디까지 지우나, cascade와 orphanRemoval은 soft delete에 쓰나 | [qna/2026-10-30-delete-scope-cascade-orphan.md](qna/2026-10-30-delete-scope-cascade-orphan.md) |
| 2026-10-30 | soft delete 필터와 연관 조회, fetch join으로 지운 데이터를 거르면 되는가 | [qna/2026-10-30-soft-delete-fetch-join.md](qna/2026-10-30-soft-delete-fetch-join.md) |
| 2026-10-29 | ADR은 무엇이고 번호가 겹치면 왜 문제인가 | [qna/2026-10-29-adr-number-collision.md](qna/2026-10-29-adr-number-collision.md) |
| 2026-10-29 | 입장 토큰의 회차 ID 비교를 Gateway에서 할지 서비스에서 할지 | [qna/2026-10-29-entry-token-schedule-check-location.md](qna/2026-10-29-entry-token-schedule-check-location.md) |
| 2026-10-29 | Flyway는 무엇이고 마이그레이션 파일은 어떻게 쓰는가 | [qna/2026-10-29-flyway-basics.md](qna/2026-10-29-flyway-basics.md) |
| 2026-10-29 | 준현 님 결제 계약 문서(contracts.md)는 무엇을 확인해 달라는 것인가 | [qna/2026-10-29-payment-contracts-plain.md](qna/2026-10-29-payment-contracts-plain.md) |
| 2026-10-29 | 엔티티 연관관계(객체 vs ID), enum 저장, 검증 위치(DTO vs 엔티티) | [qna/2026-10-29-entity-association-enum-validation.md](qna/2026-10-29-entity-association-enum-validation.md) |
| 2026-10-28 | 공연·좌석 ERD를 예시 데이터로 읽는 법 | [qna/2026-10-28-concert-seat-erd-reading.md](qna/2026-10-28-concert-seat-erd-reading.md) |
| 2026-10-27 | 공연·좌석 영역을 페르소나·요청 흐름·트래픽부터 잡고 DB를 보는 방법 | [qna/2026-10-27-concert-seat-requirements-approach.md](qna/2026-10-27-concert-seat-requirements-approach.md) |
| 2026-10-27 | 초기 환경 통합 뒤에는 어떤 방식으로 개발하고, Linear·Git은 어떻게 연결해 올리는가 | [qna/2026-10-27-dev-workflow-after-integration.md](qna/2026-10-27-dev-workflow-after-integration.md) |
| 2026-10-27 | 멀티모듈로 옮길 때 .gitignore는 어떻게 하고, gradle/ 폴더는 무엇인가 | [qna/2026-10-27-gitignore-and-gradle-wrapper.md](qna/2026-10-27-gitignore-and-gradle-wrapper.md) |
| 2026-10-27 | 프로젝트 시작 전 공통 개발 환경은 어떻게 세팅하는가 | [qna/2026-10-27-common-dev-environment-setup.md](qna/2026-10-27-common-dev-environment-setup.md) |
