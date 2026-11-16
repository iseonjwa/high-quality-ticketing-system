# 트러블슈팅

발표에 쓸 트러블슈팅 대표 사례를 한 장씩 정리한다. 정본은 [`troubleshooting.md`](../../troubleshooting.md)의 TS 항목이고, 여기에는 발표용 요약과 그림을 둔다.

## 한 장 틀

사례 하나에 파일 하나(`YYYY-MM-DD-<주제>.md`)를 두고 아래 순서로 쓴다.

1. 증상: 무엇이 어떻게 실패했는지. 오류율, 지연, 재현 조건을 수치로 쓴다.
2. 원인: 어떻게 찾았는지(로그, 지표, 재현)와 찾은 원인.
3. 해결: 바꾼 것과 다른 선택지를 고르지 않은 이유.
4. 결과: 해결 전후 수치.
5. 근거: TS 번호, PR, 측정 기록 링크.

| 날짜 | 파일 | 내용 | TS |
|---|---|---|---|
| 2026-11-16 | [2026-11-16-seat-query-entity-connection-hold.md](2026-11-16-seat-query-entity-connection-hold.md) | 좌석 조회가 초당 수십 건에 무너진 원인이 느린 쿼리가 아니라 트랜잭션 안 객체 3만 개 생성과 커넥션 점유였음(효승) | TS-75 |
| 2026-11-16 | [2026-11-16-stale-statistics-nested-loop.md](2026-11-16-stale-statistics-nested-loop.md) | 같은 코드인데 새 회차에서만 4배 느림: 낡은 통계로 Nested Loop, 좌석 생성 직후 ANALYZE로 해결(효승, ISE-284) | TS-76 |
| 2026-11-16 | [2026-11-16-large-response-gateway-limit.md](2026-11-16-large-response-gateway-limit.md) | 서비스는 17ms인데 Gateway 503: 큰 응답이 동시 처리 자리를 잡음, 상한 64 유지 근거(효승) | TS-77 |
| 2026-11-16 | [2026-11-16-breaker-blamed-for-seat-query.md](2026-11-16-breaker-blamed-for-seat-query.md) | 좌석 조회 실패를 인증 차단기 탓으로 볼 뻔했는데, 차단기가 재는 구간을 소스로 확인해 원인(서비스 504)과 결과(차단기 OPEN)를 가름(효승) | TS-78 |
