# junhyun 학습 노트

질문 노트는 `qna/YYYY-MM-DD-<slug>.md`, 하루 정리는 `daily/YYYY-MM-DD.md`에 쓴다. 최신이 위로 온다.

## 하루 정리

| 날짜 | 정리 |
|---|---|
| 2026-10-23 | [프로젝트 첫날: SA 1차 범위, 서비스 경계, 교차 검토, 문서화 체계](daily/2026-10-23.md) |

## 블로그 초안

| 날짜 | 글 | 상태 |
|---|---|---|
| 2026-10-23 | [TIL 15일차 - 티켓팅 서비스를 몇 개로 나눌까, 타임아웃은 실패가 아니다](blog/2026-10-23-til-15.html) | Tistory 임시저장 (카테고리: 백엔드 심화 TIL) |

## 질문 노트

| 날짜 | 질문 | 노트 |
|---|---|---|
| 2026-10-23 | M1(Kafka 비동기 접수 설계)을 자세히 설명해 줘 | [sa-review/03-seat-hold-kafka-design.md](../../sa-review/03-seat-hold-kafka-design.md) |
| 2026-10-23 | 원래 PG사의 타임아웃은 어떻게 처리돼? | [sa-review/02-pg-timeout-handling.md](../../sa-review/02-pg-timeout-handling.md) |
| 2026-10-23 | 알림을 넣으면 새 서비스가 생기나, 범위는 어떻게 넓히나 | [qna/2026-10-23-notification-service-scope.md](qna/2026-10-23-notification-service-scope.md) |
| 2026-10-23 | 대기열에 RabbitMQ를 쓰면 안 되나, RabbitMQ는 필수인가 | [qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md](qna/2026-10-23-waiting-queue-redis-vs-rabbitmq.md) |
| 2026-10-23 | 발제 추천을 무시하면 서비스를 몇 개로? 좌석·예매를 한 서비스에 두는 이유, Queue 분리 이점 | [qna/2026-10-23-ticketing-service-boundaries.md](qna/2026-10-23-ticketing-service-boundaries.md) |

PG 타임아웃과 M1 노트는 이 체계를 만들기 전에 SA 검토 문서와 함께 `docs/sa-review/`에 저장했다. 팀 설계 검토와 이어지는 내용이라 위치는 옮기지 않고 링크만 둔다.
