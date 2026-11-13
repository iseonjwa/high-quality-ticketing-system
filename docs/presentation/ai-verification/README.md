# AI 활용·검증 대표 사례

발제 필수 항목이다. 발표 자료에 팀당 AI 활용·검증 대표 사례가 2건 이상 들어가고 심사 랭킹에 반영된다([`ai-judgments/`](../../ai-judgments/README.md) 첫 단락). 사례는 판단 기록 중 `presentation: 후보`인 것에서 고르고, `검증` 칸(빌드·테스트·측정 결과)이 채워진 것을 우선한다.

## 한 장 틀

사례 하나에 파일 하나(`YYYY-MM-DD-<주제>.md`)를 두고, 발제가 요구하는 다섯 가지를 이 순서로 쓴다.

1. 해결하려던 작업
2. Claude Code 활용 방식(무엇을 맡기고 어떻게 물었는지)
3. 채택·수정·기각 이유
4. 빌드·테스트·API 검증 결과
5. 팀이 직접 내린 MSA 설계 판단

| 날짜 | 파일 | 사례 | 판단 기록 |
|---|---|---|---|
| 2026-11-13 | [2026-11-13-ai-code-review-method.md](2026-11-13-ai-code-review-method.md) | AI가 만든 코드를 어떻게 검증했나(다섯 겹, 숫자, 한계). 사례가 아니라 방법 한 장. 정본은 [`guides/ai-code-review.md`](../../guides/ai-code-review.md) | — |
| 2026-11-13 | [2026-11-13-gateway-overload-ai-rejection.md](2026-11-13-gateway-overload-ai-rejection.md) | 가안 1. Gateway CPU 포화: 유입 제한 추천 거부 → 차단기 실험 실패 → 동시 처리 상한 64(128 거부). 강민 확인 전 | [kangmin 11/11](../../ai-judgments/kangmin/2026-11-11-breaker-first-for-gateway-cpu-saturation.md) |
| 2026-11-13 | [2026-11-13-seat-concurrency-ai-premise-and-tool.md](2026-11-13-seat-concurrency-ai-premise-and-tool.md) | 가안 2. 좌석 동시성: AI 전제 검토 수용, JUnit 부하 근사 거부 후 k6. 권우 확인 전 | [kwonwoo 11/4](../../ai-judgments/kwonwoo/2026-11-04-k6-over-junit-load-approximation.md) |
| 2026-11-13 | [2026-11-13-case-candidates.md](2026-11-13-case-candidates.md) | 후보 8건, 고른 기준, 기록 공백 | — |
