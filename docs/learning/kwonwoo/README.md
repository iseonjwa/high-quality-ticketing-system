# kwonwoo 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-11-11 | CPU 상한을 건 스트레스에서 Gateway가 OOMKilled로 죽은 원인은 무엇인가 | [qna/2026-11-11-gateway-oomkilled-under-cpu-limit.md](qna/2026-11-11-gateway-oomkilled-under-cpu-limit.md) |
| 2026-11-11 | local 부하 측정에서 CPU 상한을 걸어야 하는 이유는 무엇인가 | [qna/2026-11-11-why-cpu-limit-in-load-test.md](qna/2026-11-11-why-cpu-limit-in-load-test.md) |
| 2026-11-11 | 좌석 선점 k6 부하 측정을 돌아보면 불필요한 단계와 빠진 개선은 무엇이었나 | [qna/2026-11-11-k6-seathold-scenario-retrospective.md](qna/2026-11-11-k6-seathold-scenario-retrospective.md) |
| 2026-11-10 | 잠금이 걸린 데이터에 트래픽을 몰아 보내는 부하 테스트로 무엇을 얻을 수 있나 | [qna/2026-11-10-hot-lock-load-test.md](qna/2026-11-10-hot-lock-load-test.md) |
| 2026-11-10 | K1 단계 측정(F)까지 끝낸 뒤 성능을 더 끌어올릴 방법은 무엇인가 | [qna/2026-11-10-performance-beyond-k1-results.md](qna/2026-11-10-performance-beyond-k1-results.md) |
| 2026-11-10 | 좌석 조회 P4a에서 dev 코드와 측정 브랜치 코드는 무엇이 다른가 | [qna/2026-11-10-p4a-jpql-vs-native-projection.md](qna/2026-11-10-p4a-jpql-vs-native-projection.md) |
| 2026-11-10 | k6 스트레스 측정에서 올린 것은 동시 사용자 수인가, 도착률인가 | [qna/2026-11-10-arrival-rate-vs-concurrent-users.md](qna/2026-11-10-arrival-rate-vs-concurrent-users.md) |
| 2026-11-09 | git bundle로 브랜치를 옮기는 방법과 push와의 차이 | [qna/2026-11-09-git-bundle-vs-push.md](qna/2026-11-09-git-bundle-vs-push.md) |
| 2026-11-09 | 성능 측정 도중 측정 기기를 바꾸면 결과가 부정확해지는가 | [qna/2026-11-09-k6-measurement-machine-change.md](qna/2026-11-09-k6-measurement-machine-change.md) |
| 2026-11-09 | Windows 시계를 맞추자 통과한 테스트 4건은 왜 실패했나 | [qna/2026-11-09-host-clock-drift-test-failures.md](qna/2026-11-09-host-clock-drift-test-failures.md) |
| 2026-11-07 | 선점만 재는 K1-H 측정에서 Gateway 서킷 브레이커가 왜 문제가 되는가 | [qna/2026-11-07-k1h-gateway-circuit-breaker.md](qna/2026-11-07-k1h-gateway-circuit-breaker.md) |
| 2026-11-05 | 기존 K1 부하 테스트는 어떻게 진행됐고, 선점만 떼어 재는 방식(K1-H)은 무엇이 다른가 | [qna/2026-11-05-k1-vs-hold-only-scenario.md](qna/2026-11-05-k1-vs-hold-only-scenario.md) |
| 2026-11-05 | 좌석 선점 부하 테스트에서 타임아웃이 나는 원인과 캐시 도입 지점 | [qna/2026-11-05-seathold-loadtest-timeout-and-cache-points.md](qna/2026-11-05-seathold-loadtest-timeout-and-cache-points.md) |
| 2026-11-05 | 좌석 조회 경량화(P4)를 먼저 하면 단계별 개선 수치화가 불가능한가, G1 GC는 무엇인가 | [qna/2026-11-05-p4-first-stepwise-measurement-and-g1.md](qna/2026-11-05-p4-first-stepwise-measurement-and-g1.md) |
| 2026-11-04 | K1 측정의 Stop-the-World는 부하 테스트 환경 탓인가, 실제 환경에서도 생기는가 | [qna/2026-11-04-seat-map-gc-stop-the-world.md](qna/2026-11-04-seat-map-gc-stop-the-world.md) |
| 2026-11-04 | 좌석 선점 부하 계획(P1~P7) 밖에서 더 해 볼 수 있는 성능 개선은 무엇인가 | [qna/2026-11-04-seathold-performance-beyond-plan.md](qna/2026-11-04-seathold-performance-beyond-plan.md) |
| 2026-11-03 | 접수형 선점에 Kafka를 도입할 때 코드를 infrastructure에 넣으면 되는가 | [qna/2026-11-03-seathold-kafka-placement.md](qna/2026-11-03-seathold-kafka-placement.md) |
| 2026-10-30 | reservation-service 내부에서 좌석 선점(seathold)과 예매(reservation)를 별도 도메인 패키지로 나누는 설계의 장단점 | [qna/2026-10-30-reservation-seathold-module-boundary.md](qna/2026-10-30-reservation-seathold-module-boundary.md) |
| 2026-10-29 | 입장 토큰의 회차 ID와 요청 회차 ID 비교를 Gateway와 서비스 중 어디서 할까 | [qna/2026-10-29-entry-token-schedule-check-placement.md](qna/2026-10-29-entry-token-schedule-check-placement.md) |
| 2026-10-29 | 좌석 선점 API에서 멱등 재요청(Idempotency Retry)이 뜻하는 것과 케이스별 응답 설계 | [qna/2026-10-29-seat-hold-idempotency-retry.md](qna/2026-10-29-seat-hold-idempotency-retry.md) |
| 2026-10-28 | 예매 시스템 설계 정리와 잠재적 허점은 없는지, 보완 사항 점검 | [qna/2026-10-28-reservation-system-design-review.md](qna/2026-10-28-reservation-system-design-review.md) |
| 2026-10-27 | Kafka 공통 코드(Outbox·중복 처리·DLQ)를 common에 둘까, 분리할까, 서비스마다 짤까 | [qna/2026-10-27-kafka-common-messaging.md](qna/2026-10-27-kafka-common-messaging.md) |
| 2026-10-27 | 공통 모듈을 왜 여러 개로 나누나, 하나로 두는 것과 무엇이 다르고 어떻게 나누나 | [qna/2026-10-27-common-module-split.md](qna/2026-10-27-common-module-split.md) |
