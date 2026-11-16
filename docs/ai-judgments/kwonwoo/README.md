# kwonwoo AI 판단 기록

kwonwoo가 AI 응답·제안에 내린 판단이다. 작성 절차는 [`ai-judgment` 스킬](../../../.agents/skills/ai-judgment/SKILL.md)을 따른다. 최신이 위로 온다. `공통`이 `예`인 기록은 [팀 공통 판단](../README.md#팀-공통-판단)에도 올라간다.

## 개별 기록

거부 판단은 굵게 표시한다.

| 날짜 | 판단 대상 | AI | 판단 | 공통 | 발표 후보 | 기록 |
|---|---|---|---|---|---|---|
| 2026-11-13 | ISE-17 소비자의 30초 시한 경합은 추천안(요청 행 잠금)을, 잠금 시한은 AI 추천(500ms) 대신 지표가 나은 100ms를 고름 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-13-ise17-consumer-lock-and-timeout-by-metrics.md) |
| 2026-11-13 | 지표 비용은 시계열 수에서 나온다는 분석을 받아 ISE-277을 SLO 버킷 방식(reservation-service만, 버킷 8개)으로 ISE-17보다 먼저 처리함 | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-13-ise277-slo-buckets-before-ise17.md) |
| 2026-11-13 | 접수형 소비자(ISE-17)보다 입장 토큰 소모(ISE-207) 선점 쪽 구현을 먼저 함(이벤트 형식을 읽는 쪽이 없을 때 바꿈) | Claude Code | 수용 | 예 | 아님 | [기록](2026-11-13-ise207-before-ise17.md) |
| 2026-11-13 | **입장 토큰 소모 뒤 `issued` 정리를 AFTER_COMMIT 리스너(ADR 0053 초안) 대신 트랜잭션 밖 메서드 호출로 바꾸는 대기열 담당 제안에 동의(커넥션 점유)** | Claude Code | 거부 | 예 | 후보 | [기록](2026-11-13-entry-token-discard-by-method-not-listener.md) |
| 2026-11-12 | 모드 전환 뒤 같은 키 재요청 500 빈틈을, 접수형으로 시작한 요청은 202로 답하는 C안으로 막음(다섯 번의 반문으로 대안 검증) | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-12-sync-replay-of-async-requests.md) |
| 2026-11-12 | 접수형 선점(ISE-16)의 남은 결정 다섯 가지를 추천안대로 정함(접수 단계 409 즉시 거절, 기동 시 모드, 조회 API 상시 개방, 릴레이 제약·직접 발행은 ISE-17·18로) | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-12-async-seat-hold-intake-decisions.md) |
| 2026-11-12 | 접수형 선점에서 구역이 섞인 요청을 거부하자는 첫 추천 대신, 가장 작은 구역을 키로 쓰는 2안을 고름(일행 예매 원자성 근거 보강) | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-12-async-seat-hold-mixed-section-key.md) |
| 2026-11-12 | 좌석 배치는 로컬 캐시가 Redis보다 낫다는 AI 설명을 그대로 받지 않고, Redis로도 구현해 측정으로 비교하게 함 | Claude Code | 보류 | 예 | 후보 | [기록](2026-11-12-verify-local-vs-redis-layout-cache-by-measurement.md) |
| 2026-11-10 | 좌석 조회 경량화는 담당자(반효승)가 dev에 넣은 P4a를 살려 진행하고, 측정 브랜치 코드는 참고로만 둠 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-10-adopt-owner-p4a-keep-measurement-code-as-reference.md) |
| 2026-11-09 | P4d 조건이 맞았지만 K1 좌석 조회 개선을 P4c에서 마치고 K1-H로 넘어감 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-09-k1-stop-at-p4c-skip-p4d.md) |
| 2026-11-09 | **측정 기기를 옮길 때 번들 대신 브랜치를 푸시하고, 세션 핸드오프 메모는 푸시하지 않고 직접 옮김** | Claude Code | 거부 | 예 | 아님 | [기록](2026-11-09-push-measurement-branches-keep-handoff-local.md) |
| 2026-11-09 | k6 측정을 다른 기기로 옮긴 뒤, 이 기기에서는 AI가 추천한 정합성 대사(ISE-14)를 먼저 구현 | Claude Code | 수용 | 아니오 | 아님 | [기록](2026-11-09-ise14-reconciliation-while-measuring-elsewhere.md) |
| 2026-11-07 | K1-H를 막은 Gateway 인증 차단기를 측정 환경에서만 완화(느린 호출 판정 끔)하고, Gateway 담당과의 조율은 측정이 끝난 뒤로 보류 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-07-k1h-relax-gateway-breaker-in-measurement.md) |
| 2026-11-06 | k6 단계별 개선을 수정 없는 기본 코드에서 시작해 병목을 직면하는 순서로 다시 설계(G1·R1·S4도 단계로, 워밍업 1분, 최종도 local) | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-06-k6-steps-from-unmodified-base.md) |
| 2026-11-06 | 좌석 조회 경량화(P4) 측정을 반효승 담당에서 일부 가져와 좌석 선점과 같은 환경(측정 브랜치)에서 재고, 본 구현은 ISE-189에 둠 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-06-measure-p4-seat-map-in-own-environment.md) |
| 2026-11-05 | P4를 바로 시작하자는 제안 대신 G1 GC로 먼저 재 보고, 정상 결과가 나오면 기존 순서를, 안 나오면 P4를 먼저 하기로 함 | Claude Code | 부분 수용 | 예 | 후보 | [기록](2026-11-05-g1-first-then-decide-p4-order.md) |
| 2026-11-04 | **JUnit 부하 근사(`SeatHoldLoadTest`) 대신 k6로 부하 테스트를 다시 하기로 하고, 대기열 초당 100명 입장 기준 병목·단계별 개선 계획 작성** | Claude Code | 거부 | 예 | 후보 | [기록](2026-11-04-k6-over-junit-load-approximation.md) |
| 2026-11-03 | ISE-79 동시성 시나리오(S0~S4, C1~C7, L1~L6)와 이슈 전제 검토를 수용하고 워크트리에서 단계별 실측 | Claude Code | 수용 | 예 | 후보 | [기록](2026-11-03-ise79-scenarios-measure-in-worktree.md) |
| 2026-11-02 | 좌석·매수 반환 위치를 먼저 합의하자는 AI 권고 대신 만료 스케줄러(ISE-13)를 먼저 구현하고 논의 항목을 문서로 남김 | Claude Code | 부분 수용 | 예 | 아님 | [기록](2026-11-02-hold-expiry-implement-first.md) |
| 2026-10-30 | **ISE-11 구현이 다른 담당 모듈에 걸친 부분을 contract 이슈로 알리고 유지하자는 AI 권고를 거부하고 선점 모듈 안으로 격리** | Claude Code | 거부 | 예 | 아님 | [기록](2026-10-30-seathold-isolate-other-owners.md) |
| 2026-10-30 | 포트폴리오 트러블슈팅 서사 확보를 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 MVP 이후 고도화(M4)로 분리 (ADR 0035) | Antigravity (Gemini) | 수용 | 예 | 후보 | [기록](2026-10-30-mvp-and-troubleshooting-scope-split.md) |
| 2026-10-30 | 트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙 수용 (ADR 0034) | Antigravity (Gemini) | 부분 수용 | 예 | 후보 | [기록](2026-10-30-seathold-module-separation.md) |
| 2026-10-29 | 좌석 선점 결과 계약(ISE-21): 동기형 멱등 재요청 처리 규칙(실패 시 롤백·동시 PK 충돌 재조회) 수용 및 응답에 `seatIds` 포함 결정 | Antigravity (Gemini) | 수용 | 예 | 후보 | [기록](2026-10-29-seat-hold-contract-and-idempotency.md) |
| 2026-10-27 | 공통 모듈: SA 4개 분리 대신 common 하나로 두고 메시징만 나중에 분리하는 AI 추천을 수용 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-27-common-module-minimal-split.md) |
| 2026-10-27 | Gateway 스택: 현재 WebMVC 대신 SA 전제와 AI 추천대로 WebFlux로 전환 | Claude Code | 수용 | 예 | 후보 | [기록](2026-10-27-gateway-webflux.md) |
| 2026-10-27 | 멀티모듈 빌드: AI 진단·수정안(common 라이브러리화, 공통 의존성 루트 집약) 수용 | Claude Code | 수용 | 예 | 아님 | [기록](2026-10-27-multimodule-build-fix.md) |

## 일지

작은 판단을 하루 한 파일에 한 줄씩 남긴다.

| 날짜 | 일지 |
|---|---|
| 2026-11-12 | [일지](daily/2026-11-12.md) |
| 2026-11-11 | [일지](daily/2026-11-11.md) |
| 2026-11-10 | [일지](daily/2026-11-10.md) |
| 2026-11-09 | [일지](daily/2026-11-09.md) |
| 2026-11-07 | [일지](daily/2026-11-07.md) |
| 2026-11-06 | [일지](daily/2026-11-06.md) |
| 2026-11-05 | [일지](daily/2026-11-05.md) |
| 2026-11-04 | [일지](daily/2026-11-04.md) |
| 2026-11-02 | [일지](daily/2026-11-02.md) |
| 2026-10-30 | [일지](daily/2026-10-30.md) |
