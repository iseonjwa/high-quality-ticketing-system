---
title: 다른 조 저장소 5곳에서 배울 점과 우리 프로젝트에 적용할 것
date: 2026-11-13
updated: 2026-11-13
asker: Junhyun An
level: 중급
tags: [presentation, load-test, review, archunit, saga, comparison]
---

# 다른 조 저장소 5곳에서 배울 점과 우리 프로젝트에 적용할 것

## 질문
"다른 조 레포들인데, 다른 조에서도 좋은 점 싹싹 뽑아서 우리 프로젝트에 적용할 만한 점으로 정리해 줘." 대상은 HEALO-WORLD/ITDA, 500-internal-server-error-team/commerce-settle, gongmoju-4jo/ipo-subscription(4조), Good-Ticket-Service/ticket, dont-worrys/travelpay다.

## 짧은 답
남은 5일(11/18 제출) 안에 효과가 큰 것은 새 기능이 아니라 **이미 한 일을 증거로 보이는 형식**이다. 다섯 팀에서 반복해서 보인 것은 다음 다섯 가지다.

1. **측정 전에 판정 기준을 고정한다.** 합격·목표·무효·판정 불가를 나누고, k6가 합격 조건을 어기면 종료 코드로 실패한다(4조, Good-Ticket). ISE-264 측정 전에 기준표를 먼저 써야 한다.
2. **전/후 표 하나에 원인·대가·환경·한계를 같이 쓴다.** 예: 3회 중앙값, 건당 비용 분해, "DB CPU +10~15%라는 대가"(ITDA #258, Good-Ticket #418, travelpay #299).
3. **실패 시나리오를 "상황 → 주입 → 기대 → 실제 → 근거 테스트" 표로 보인다.** 발제의 SAGA 실패 시나리오 문서와 보상 증거를 한 장으로 채운다(travelpay, ITDA, Good-Ticket).
4. **"도입하지 않은 것"과 "알려진 한계" 표.** 심사 질문을 미리 막는다(travelpay D8, ITDA 향후 개선).
5. **계층 규칙을 ArchUnit으로 고정한다.** 발제 필수인 4계층 검증을 사람 점검에서 자동 검사로 올린다(commerce-settle 4개 서비스, Good-Ticket 공통 규칙).

## 자세히

### 다섯 팀 한눈에 보기 (2026-11-13 GitHub 조회)
| 팀 | 도메인 | PR(머지) | 리뷰가 남은 PR, 리뷰 수 | 테스트 | 눈에 띄는 것 |
|---|---|---|---|---|---|
| 우리(5조) | 콘서트 티켓팅 | 316(313) | 12개, 21건 | `@Test` 1,833 | ADR 58, TS 69, AI 판단 170(거부 55), 배포 서버 측정, Synthetic Data |
| ITDA | 의료기관 검사 의뢰(B2B) | 140(137) | 0개 | `@Test` 1,290 | README를 발표용으로 구성(mermaid 5개), 측정 PR 형식 |
| commerce-settle | 커머스 정산 | 195(181) | 51개(26%), 89건(사람) | 테스트 파일 233 | ArchUnit 4개, CI 통합 시나리오 matrix, PR 본문 형식 검사 |
| 4조 | 공모주 청약 | 81(70) | 76개, 384건(CodeRabbit 190) | 테스트 파일 약 141 | 판정 기준 사전 고정, Synthetic Data, 정합성 SQL 22종 |
| Good-Ticket | 공연 티켓 | 201(197) | 65개(32%), 85건(사람) | 테스트 파일 약 303 | 같은 도메인, 측정 오염 방지, 합격 조건 k6, 동시성 리뷰 에이전트 |
| travelpay | 외화 지갑·송금 | 172(171) | 120개(70%), 328건(CodeRabbit 212) | 테스트 파일 291 | 설계 결정 5칸 표, 정합성 불변식 표, 알려진 한계 표 |

### 1. 측정을 믿게 만드는 장치
| 배울 점 | 어디서 | 우리에게 적용 | 5일 안 | 이슈 |
|---|---|---|---|---|
| 판정 기준을 측정 전에 표로 고정. 합격·목표·무효(요청 수가 기대와 다름)·판정 불가(오류 섞임)를 나누고 "측정 후에는 바꾸지 않는다"고 적음 | 4조 `docs/LOADTEST.md` 4·7장 | ISE-264 측정 전에 `synthetic-data.md` 5절과 A0에 기준표를 먼저 둔다 | 가능, 1시간 | ISE-264 |
| k6 합격 조건을 README에 쓰고 어기면 종료 코드 99. 예: 같은 좌석 100명 → 201 정확히 1건, 409 99건, 5xx 0건 | Good-Ticket `script/seat-load/README.md` | A0·O1에 k6 `thresholds`로 합격 조건(5xx 0, 성공률)을 건다. 좌석 경합 시나리오에는 "201은 좌석당 1건"을 대사 SQL로 대조 | 가능, 2~3시간 | ISE-264, ISE-266 |
| "k6 성공 수 = DB 행 수" 대조, 잠금 대기를 `pg_stat_activity` 표본으로 셈 | Good-Ticket PR #391 | 측정 뒤 대사 SQL 결과를 표에 같이 적는다(O1 teardown이 이미 SQL을 출력한다) | 가능, 1시간 | ISE-264 |
| 측정 오염 방지: 소스 SHA 고정, 외부 컨테이너 CPU 경합 검사, k6 코어 수가 다르면 비교 안 함, 지표가 없으면 0으로 읽지 않음 | Good-Ticket `infra/integration/README.md` | 측정 환경 기록표(SA 3-1)에 회차 수, 무효 조건, 발생기 자원 사용률을 더한다 | 가능, 1시간 | ISE-264 |
| 3회 중앙값, 순서를 섞어 번갈아 측정 | 4조, travelpay `load-test/README.md`, ITDA | 이번에 새로 재는 비교(ISE-264, ISE-18)는 가능하면 2~3회, 아니면 "1회"라고 한계에 적는다 | 부분 | ISE-264, ISE-18 |
| 정합성 SQL마다 "위반만 행으로 나온다, 기대 0행", 대상이 없으면 "건너뜀" 출력 | 4조 `loadtest/sql/verify.sql` | 우리 대사·SAGA 검사 결과를 "위반 0행" 캡처로 보이고, 대상 0건을 통과로 착각하지 않게 한 줄을 넣는다 | 가능, 1~2시간 | ISE-267 |
| 처리량 정의: 접수량, 확정 TPS, 지속 가능 TPS를 나누고 미완료가 계속 늘면 지속 가능으로 안 침 | commerce-settle `docs/decisions/0002` | 접수형(202) 수치를 말할 때 "접수 응답"과 "처리 완료(컨슈머 랙)"를 나눠 적는다(SA 3장 오류 정의와 같은 방향) | 가능, 30분 | ISE-18 |

### 2. 발표에서 결과를 보이는 형식
| 배울 점 | 어디서 | 우리에게 적용 | 5일 안 | 이슈 |
|---|---|---|---|---|
| 측정 PR 형식: 요약 전/후 표(3회 중간값) → 원인(지표로 건당 비용 분해) → 작업 → 남은 병목. 예: Outbox 대기 p95 286초 → 1.35초 | ITDA PR #258 | `performance/` 한 장마다 이 순서로. Gateway(강민)·좌석(권우) 장표의 틀 | 가능, 1시간 | ISE-266, ISE-269 |
| 전/후 표에 대가를 숨기지 않음. 예: 확정 p95 24초 → 2.7초, 대가 DB CPU +10~15%. 조건부 UPDATE로 평균 231 → 530ms 늘어난 것도 적음 | Good-Ticket #418, ITDA #40 | 우리 표에도 "치른 대가" 칸을 둔다. 좌석 동시성은 "연타 p95 약 5ms 증가"(k6-seathold-story)를 그대로 쓴다 | 가능 | ISE-266, ISE-269 |
| 실패 시나리오 표: 상황 → 처리, 정합성 불변식 6개와 "같은 키 20건 동시 → 송금 1건" 같은 결과 | travelpay `payment-transfer.md` "정합성 불변식", ITDA `docs/SCENARIOS.md`(개요·흐름·정책·성공 기준 4블록) | SAGA 한 장: 결제 거절, 응답 유실, 만료 뒤 승인, 중복 소비, DLQ를 "주입 방법 / 기대 / 실제 / 근거 테스트" 표로. 테스트는 이미 있다 | 가능, 반나절 | ISE-267 |
| 경쟁 상태를 양방향 테스트 이름으로 보임(`refundLoses_whenAllocateCommitsFirst` / `allocateLoses_whenRefundCommitsFirst`) | 4조 `SubscriptionRefundRaceTest` | 우리 경쟁 테스트(C3~C7, 결제 재시작 시나리오) 이름을 표로 옮긴다. 새 테스트는 필요 없다 | 가능, 1시간 | ISE-267 |
| "도입하지 않은 것" 표(무엇을, 왜 안 했나)와 "알려진 한계" 표(각 줄이 어떤 지표로 드러나는지) | travelpay `payment-transfer.md` D8·알려진 한계, ITDA README 향후 개선, commerce-settle README "목표를 실적으로 안내하지 않는다" | `considerations/`에 한 장: 예) 벡터 DB를 안 씀(ADR 0049), EC2 한 대(ADR 0047), 브로커 1대라 브로커 유실은 범위 밖(SA 3장), 커밋 응답 유실 시 재결제 불가(ADR 0048) | 가능, 반나절 | 새로 나눌 일 |
| 파티션 근거를 3~4줄 문장으로: 키, 파티션 수, 리스너 스레드에서 PG를 기다리지 않아 동시 처리가 파티션 수에 묶이지 않음, 토픽마다 컨슈머 그룹 분리 | Good-Ticket `docs/architecture.md` 6절 | ISE-265 표 아래에 같은 형식의 문단 | 가능, 1시간 | ISE-265 |
| 알림 규칙 표: 무엇이 깨지면 → 어떤 지표 → 어떤 알림 | travelpay "알림 규칙", `transfer-alerts.yml` | 발제 "모니터링" 장에 우리 Alertmanager 규칙을 이 표로(대시보드 캡처와 함께) | 가능, 반나절 | ISE-272 |
| README를 발표 입구로: 목차, 기능표, mermaid 흐름 5개, 그림 캡션, 트러블슈팅 "문제·개선·의의" 3단, 마지막에 향후 개선 | ITDA README, commerce-settle `docs/diagrams/*.svg` 14개 | README 첫 화면에 구조도·결과 요약·접속 주소·발표 자료 링크 | 가능, 2시간 | ISE-272 |

### 3. AI 검증과 리뷰
| 배울 점 | 어디서 | 우리에게 적용 | 5일 안 | 이슈 |
|---|---|---|---|---|
| 리뷰 지적에 "반영했습니다(커밋 SHA)" 또는 "이번에는 그대로 둔다 + 이유"로 답하고 재확인 뒤 닫음. 사람 리뷰어의 구조 제안은 부분 수용과 후속 이슈로 | 4조 PR #100, #129 | ISE-271 규칙에 "답글에 반영 커밋 SHA"를 넣는다. 우리 판단 기록 형식과 같은 모양이라 발표 사례로 이어진다 | 가능 | ISE-271 |
| 읽기 전용 동시성 리뷰 에이전트 체크리스트. 우리 프롬프트에 없는 것: 잠근 뒤 상태 재확인, 비멱등 API에 붙은 재시도, `@Transactional` 자기 호출·전파·readOnly 오용 | Good-Ticket `.claude/agents/concurrency-reviewer.md` | `docs/guides/ai-code-review-prompt.md` 3번 항목에 세 줄을 더한다 | 가능, 30분 | ISE-263 |
| PR 본문에 "동시성 리뷰(읽기 전용 에이전트)에서 이중 선점은 찾지 못했다"처럼 AI 리뷰 결과 한 줄 | Good-Ticket PR #365 | ISE-271 1번 안의 최소형으로 쓸 수 있다 | 가능 | ISE-271 |
| 변이 검사 결과를 "6개 중 5개를 잡았다"처럼 살아남은 변이와 이유까지 적음 | Good-Ticket #418 | 우리는 변이 확인을 해 왔다(PR #88 7건, Gateway 6건). 살아남은 변이도 적도록 가이드 L1에 한 줄 | 가능, 10분 | ISE-263 |

### 4. 코드 품질 장치
| 배울 점 | 어디서 | 우리에게 적용 | 5일 안 | 이슈 |
|---|---|---|---|---|
| ArchUnit 계층 규칙: domain은 presentation·application·infrastructure·Spring·Kafka에 의존하지 않고, application은 presentation·infrastructure에 의존하지 않음. 빈 계층 허용 | commerce-settle `*/ArchitectureTest.java` 4개, Good-Ticket `LayeredArchitectureRules` | 계층형 검증 대상이던 user-service부터 한 파일. 위반이 나오면 그 목록이 발표 재료다(예: 앞서 본 `ReservationService` → `seathold.application` import) | 부분, 2~3시간 | 새로 나눌 일 |
| CI 중복 실행 취소(`concurrency` + `cancel-in-progress`) | ITDA, travelpay, commerce-settle | 우리 `build.yml`에는 없다(`images.yml`에만 있음). 몇 줄 | 가능, 10분 | 새로 나눌 일 |
| PR 본문 CI 결과 구간 자동 갱신 | 4조 `.github/scripts/update-pr-status.js` | PR마다 검증 근거가 본문에 남는다. 쓰기 권한 설정이 필요하다 | 부분, 1~2시간 | ISE-271과 같이 |
| Synthetic CSV를 생성 코드로 다시 만들면 커밋한 파일과 바이트로 같은지 테스트 | 4조 `loadtest/synthetic/tests` | 우리 `test_synthetic.py`는 같은 시드 결정성만 보고 커밋한 CSV와의 일치는 보지 않는다. 한 개 테스트 | 가능, 30분 | ISE-262 |
| 바뀐 서비스만 빌드하는 경로 필터 + 마지막 집계 job을 필수 체크로 | ITDA `ci.yml` | 우리도 필수 검사에 경로 필터를 걸면 체크가 안 생기는 문제를 겪었다(`git-workflow.md` 5절). 지금 방식으로 충분 | 필요 없음 | — |

### 따라 하지 말 것
- **Kafka·SAGA를 빼는 선택**(travelpay D8): 그 팀은 원장 두 줄을 한 DB 트랜잭션으로 처리해 성립한다. 우리 발제는 Kafka 적재·SAGA·파티션 근거를 요구한다. "어디에 필요하고 어디엔 필요 없나" 표 형식만 가져온다.
- **한 파일에 다 쓰는 거대 문서**(travelpay `payment-transfer.md` 약 158KB, commerce-settle `development.md` 약 183KB): 발표 5일 전에는 한 장 요약과 근거 링크가 낫다.
- **봇 리뷰 건수를 품질 증거로 내세우기**(4조 384건 중 190건이 봇, 사람 승인은 짧은 코멘트도 많다): 건수가 아니라 "지적 → 판단 → 커밋" 사례를 내세운다.
- **k6 스크립트에 서버 공인 IP 하드코딩**(ITDA `load-test/k6/reservation-saga.js`): 우리는 사설 IP를 환경변수로 준다.
- **측정 기록을 외부 링크(claude.ai artifact)로만 두기**(travelpay): 저장소 정본 원칙(AGENTS.md 대전제 4)과 맞지 않는다.
- **다른 팀 기준값을 그대로 가져오기**(4조 처리량 200건/초 등): 서비스와 부하 모양이 다르다. 형식만 본뜬다.
- **5일 안에 새 측정 체계 도입**(4조 워밍업 기울기 판정, 번갈아 3회 자동화): 도구 자체 테스트까지 갖춘 체계라 지금 따라 하면 오류 위험이 크다. 서술 방식만 참고한다.

### 우리가 앞선 것 (발표에서 숨기지 말 것)
- **기록 체계:** ADR 58개(commerce-settle 17개, 나머지는 폴더 없음), 트러블슈팅 69개, AI 판단 기록 170건(거부 55). 다섯 팀 모두 저장소에서 AI 설계 검증 로그를 찾지 못했다(Notion 등 저장소 밖은 미확인).
- **Synthetic Data:** 저장소에 산출물이 있는 팀은 우리와 4조뿐이다.
- **배포 서버 측정:** 다른 팀 측정은 대부분 노트북 한 대에서 했다(travelpay·4조·Good-Ticket 문서가 스스로 밝힘). 우리는 EC2 배포 서버와 별도 발생기 EC2에서 쟀다.
- **운영 자동화:** 배포 실패 시 자동 롤백(4조는 수동), Prometheus·Loki 규칙 테스트가 CI에서 돈다(4조는 수동 실행).
- **RAG 상담:** 다른 팀 저장소에서 찾지 못했다.

### 같은 도메인 팀(Good-Ticket) 때문에 받을 수 있는 질문
1. **"좌석 이중 선점이 없다는 걸 어떻게 증명했나?"** 조건부 UPDATE·잠금 순서의 근거, 동시성 시나리오 C4~C6 결과(0건), 그리고 k6 성공 수와 DB 선점 행 수의 대조를 한 장으로 준비한다.
2. **"측정 환경이 같은 조건인가, 발생기가 먼저 막히지 않았나?"** 발생기 EC2(c6i.xlarge)를 따로 둔 이유, 발생기 CPU·메모리 기록, 회차 수와 한계를 말한다.
3. **"Kafka 비동기 결제에서 유실·중복·늦은 승인은?"** Outbox, 처리 이력, DLQ, 대사, 만료 뒤 승인 자동 환불을 상황별 표로 보인다.

## 이 프로젝트에서는
- 적용 설계(형식 견본 T1~T11, 적용 위치·담당·완료 기준, ArchUnit 설계)는 [`docs/presentation/evidence-formats.md`](../../../presentation/evidence-formats.md)다. 새 이슈: ISE-274(CI 중복 빌드 취소), ISE-275(ArchUnit), ISE-276(도입하지 않은 것·한계).
- 이슈별로 들어갈 곳: ISE-264(판정 기준 사전 고정, k6 합격 조건, 대사 대조, 측정 신뢰도 4줄), ISE-265(파티션 근거 문장), ISE-266·269(전/후 표 형식과 대가 칸), ISE-267(실패 시나리오 표, 경쟁 테스트 이름 표, 위반 0행 캡처), ISE-271(답글에 커밋 SHA, PR 본문 AI 리뷰 한 줄), ISE-272(알림 규칙 표, README 발표 입구), ISE-262·263(Synthetic 재생성 일치 테스트, 리뷰 프롬프트 세 줄, 살아남은 변이).
- 아직 이슈가 없는 것: "도입하지 않은 것·알려진 한계" 한 장, ArchUnit 계층 규칙, `build.yml` 중복 실행 취소.

## 면접 포인트
- **다른 팀과 비교해 무엇을 배웠나?** 숫자를 많이 내는 것보다, 측정 전에 판정 기준을 고정하고 대가와 한계를 같이 적는 팀의 결과가 더 믿을 만해 보였다. 그래서 우리 측정 결과에도 기준표, 대가, 한계를 붙였다.
- **왜 ArchUnit을 도입했나(도입한다면)?** 4계층 규칙을 사람이 점검하면 다음 PR에서 다시 깨질 수 있다. 규칙을 테스트로 만들면 CI가 매번 확인한다.

## 참고
- 조사(2026-11-13): 하위 에이전트 다섯이 저장소를 읽기만 했다(클론과 `gh`). 메인이 원문을 직접 다시 확인한 것은 commerce-settle `ArchitectureTest.java` 4개, Good-Ticket `.claude/agents/concurrency-reviewer.md`와 `script/seat-load/README.md` 합격 조건, ITDA PR #258 수치, travelpay `docs/guide/payment-transfer.md`의 "정합성 불변식"·"D8. 도입하지 않은 것"·"알려진 한계" 절이다.
- 저장소: https://github.com/HEALO-WORLD/ITDA (dev 브랜치), https://github.com/500-internal-server-error-team/commerce-settle, https://github.com/gongmoju-4jo/ipo-subscription, https://github.com/Good-Ticket-Service/ticket, https://github.com/dont-worrys/travelpay
- 같은 날 노트: [최종 발표에서 심사위원은 무엇을 보나](2026-11-13-final-presentation-gaps-vs-rubric.md)
