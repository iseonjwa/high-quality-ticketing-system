# 증명 형식 견본: 다른 조에서 가져온 형식과 우리 적용 위치

발표 전에 다른 조 공개 저장소 다섯 곳(ITDA, commerce-settle, 4조, Good-Ticket, travelpay)을 읽고, 무엇을 만들었는지보다 **어떻게 증명했는지**를 가져온다. 조사 정리는 [`2026-11-13-other-teams-repos-lessons.md`](../learning/junhyun/qna/2026-11-13-other-teams-repos-lessons.md), 이 방향을 정한 판단은 [판단 기록](../ai-judgments/junhyun/2026-11-13-borrow-formats-not-stacks-from-other-teams.md)이다.

## 1. 원칙

- **스택과 결정은 가져오지 않는다.** 기술 선택은 도메인과 제약에서 나온다. Kafka·SAGA를 빼고 원장 한 트랜잭션으로 묶은 선택, ECS·Terraform, GitHub Actions 배포는 그 팀의 답이다. 우리 결정은 SA와 `docs/decisions/`가 정본이다.
- **형식과 판단 방식만 가져온다.** 측정 전에 기준을 고정하고, 전/후 표에 대가와 한계를 같이 적고, 실패를 표로 보이고, 규칙을 자동 검사로 막고, 리뷰 지적에 반영 커밋으로 답하는 방식이다.
- **다른 팀의 기준값과 숫자는 쓰지 않는다.** 기준값은 우리 설계에서 정하고, 숫자는 우리 측정만 쓴다.
- **건수를 내세우지 않는다.** 리뷰 봇 코멘트 수, 테스트 파일 수는 증명이 아니다. 리뷰 봇(CodeRabbit)은 달지 않기로 했다([판단 기록](../ai-judgments/junhyun/2026-11-13-no-coderabbit-prompt-review-only.md)).

## 2. 적용 표

견본 번호는 3절이다. 완료 기준을 채우면 그 이슈에서 체크한다.

| 견본 | 쓰는 곳 | 이슈·담당 | 완료 기준 |
|---|---|---|---|
| T1 판정 기준표 | Synthetic·A0 측정([`synthetic-data.md`](../design/infra/synthetic-data.md) 5-0절), 다른 측정도 측정 전에 | ISE-264 영은(기준은 PR #583에 있음) | 측정 전에 기준표를 읽고, 바꾸면 측정 전에 PR |
| T2 전/후 표 | 성능 개선 장표(Gateway 상한, 좌석 동시성, 접수형 비교) | ISE-269 강민, ISE-266 권우 | 표마다 "치른 대가"와 "한계" 칸이 차 있음 |
| T3 측정 신뢰도 네 줄 | 측정 결과를 적는 모든 곳 | ISE-264, ISE-266, ISE-269 | 결과 표 바로 위에 네 줄 |
| T4 실패 시나리오 표 | SAGA 실패·보상 장표 | ISE-267 효승 | 줄마다 근거 테스트 이름이나 캡처 |
| T5 경쟁 테스트 이름 표 | 좌석 동시성·결제 멱등 장표 | ISE-266 권우, ISE-267 효승 | 테스트 이름과 단언한 수 |
| T6 도입하지 않은 것·알려진 한계 | 발표 마지막 장, Q&A 대비 | ISE-276 준현(영역별 한두 줄은 각자 이슈) | 표마다 5개 이상, 근거 링크 |
| T7 파티션 근거 문단 | Kafka 장표 | ISE-265 권우 | 토픽마다 키·파티션 수·소비자 동시성·이유 |
| T8 알림 규칙 표 | 관측 장표 | ISE-272 준현 | 규칙 파일의 알림을 표로, 시험 파일 링크 |
| T9 정합성 검사 결과 | 측정 뒤, 보상 시연 뒤 | ISE-264, ISE-267 | 검사 SQL과 위반 0행 결과 |
| T10 리뷰 답글 | 남은 기간의 PR | ISE-271(팀 결정) | 지적마다 판단과 반영 커밋 SHA |
| T11 README 입구 | 저장소 README | ISE-272 준현 | 첫 화면에서 접속 주소·발표 자료·검증 근거로 감 |
| ArchUnit 계층 검사 | user·reservation·payment 서비스 테스트 | ISE-275 준현 | 4절 설계대로, 변이 확인 |
| CI 중복 빌드 취소 | `.github/workflows/build.yml` | ISE-274 준현(PR #597) | 같은 PR의 앞선 실행이 취소됨(확인함) |

## 3. 견본

### T1. 판정 기준표 (측정 전에 쓴다)

결과를 본 뒤 기준을 고르면 어떤 결과든 성공으로 보인다. 그래서 측정 전에 쓰고 PR로 남긴다. 기준을 어긴 실행을 버릴지(무효), 실패로 볼지(불합격)를 나눈다.

```markdown
| 구분 | 조건 | 기준 | 보는 곳 | 근거 |
|---|---|---|---|---|
| 무효 | 발생기 여유 | CPU 80% 이하 | 발생기 docker stats | 발생기가 막히면 서버 한계와 섞임 |
| 불합격 | 오류율 | 1% 미만 | k6 임계값(어기면 종료 코드 99) | 초안 값 |
| 정합성 | 중복 판매 | 0건 | 대사 SQL | SA 3장 |
| 비교(합격선 없음) | 처리량 | 전·후 차이 | 요약 표 | SA 3장 결정 47번 |
```

k6 임계값으로 걸 수 있는 조건은 시나리오의 `thresholds`에 같이 건다. 예: A0의 `count==1000`, `rate<0.01`.

### T2. 전/후 표

숫자만 두면 "왜 좋아졌나", "무엇을 잃었나"를 질문받는다. 원인과 대가를 같은 표에 둔다.

```markdown
| 지표 | 전 | 후 | 바꾼 것 | 원인(왜 달라졌나) | 치른 대가 |
|---|---|---|---|---|---|
| 선점 p95 | 000ms | 000ms | 000 | 000 | 연타 p95 약 0ms 증가 |

환경: 같은 서버·같은 날·같은 시나리오, 커밋 전 `abc1234` → 후 `def5678`
한계: 000(예: 한 번만 잼, 서버 한 대)
```

같은 날 같은 환경에서 잰 두 실행끼리만 %로 비교한다(Gateway 발표 소스의 숫자 사용 주의와 같다).

### T3. 측정 신뢰도 네 줄

```markdown
- 반복: 세 번 재고 처리량이 가운데인 실행을 대표값으로 씀(세 줄 모두 아래 표에)
- 환경: 배포 서버 m7i.xlarge 1대(측정 모드), 발생기 c6i.xlarge, 사설 IP, 커밋 `abc1234`, 회차 000, 2026-11-00 00:00 UTC
- 버린 실행: 1번(발생기 CPU 95%로 무효)
- 유효성 조건: 발생기 CPU 최대 00%, 메모리 00%, action_lateness p95 000ms
```

### T4. 실패 시나리오 표

```markdown
| 상황 | 주입 방법 | 기대 | 실제 | 근거 |
|---|---|---|---|---|
| PG 승인 거절 | Mock PG mode DECLINE | 결제 실패 → 좌석 원복(보상) | 예매 CANCELLED, 좌석 AVAILABLE | 테스트 `000Test`, 캡처 000.png |
| PG 응답 시한 초과 | Mock PG mode DELAY | 실패로 단정하지 않고 확인 대기 | 000 | 000 |
```

"실제" 칸을 비워 두지 않는다. 재현하지 못했으면 "미재현"이라고 쓴다.

### T5. 경쟁 테스트 이름 표

```markdown
| 경쟁 상황 | 테스트 | 동시 요청 | 단언 | 변이 확인 |
|---|---|---|---|---|
| 같은 좌석에 100명 | `<테스트 클래스.메서드>` | 100 | 201 정확히 1건, 409 99건, 5xx 0건 | 잠금을 빼면 실패 |
```

"정확히 1건"처럼 수를 단언한 테스트만 적는다. "예외가 안 났다"는 경쟁 테스트가 아니다.

### T6. 도입하지 않은 것과 알려진 한계

```markdown
#### 도입하지 않은 것
| 무엇 | 왜 안 했나 | 대신 한 것 | 근거 |
|---|---|---|---|
| 리뷰 봇(CodeRabbit) | 시간당 리뷰 한도, 우리 설계를 모르는 일반 리뷰 | 저장소 프롬프트로 다른 모델 리뷰 | 판단 기록 |

#### 알려진 한계
| 한계 | 영향 | 지금의 대응 | 다시 볼 때 |
|---|---|---|---|
| 배포 서버가 EC2 한 대 | 서비스·DB·Kafka가 CPU를 나눠 씀 | 측정 모드로 다른 부하를 끔 | 서버를 나눌 때 |
```

### T7. 파티션 근거 문단

토픽마다 서너 줄로 쓴다. 숫자만 두지 않고 "왜 그 수인가"를 쓴다.

```markdown
**`<토픽>`**: 키는 `<키>`라 같은 <대상>의 이벤트는 한 파티션에서 순서대로 처리된다. 파티션 0개, 소비자 동시성 0이다.
소비자는 리스너 스레드에서 <외부 호출>을 기다리지 않으므로 동시 처리 수가 파티션 수에 묶이지 않는다(또는 묶인다, 이유).
<다른 토픽>과 컨슈머 그룹을 나눈 이유: 000.
근거: ADR 0000, Kafka-UI 캡처 000.png
```

### T8. 알림 규칙 표

규칙 파일 `infra/prometheus/rules/`(gateway·lottery·payment)의 알림과 시험 파일 `infra/prometheus/tests/`를 그대로 표로 옮긴다.

```markdown
| 알림 | 조건(요약) | 지속 | 심각도 | 받는 사람이 할 일 | 시험 |
|---|---|---|---|---|---|
| 000 | 000 > 0 | 5m | warning | 000 | `payment-alerts.test.yml` |
```

### T9. 정합성 검사 결과

검사 SQL과 결과를 같이 둔다. 결과가 0행이면 "위반 0행"을 캡처한다.

```markdown
| 불변식 | 검사 | 결과 |
|---|---|---|
| 좌석 하나에 확정 예매는 하나 | `select seat_id, count(*) ... having count(*) > 1` | 0행 |
| 결제 승인 수 = 확정 예매 수 = SOLD 좌석 수 | O1 teardown 대사 SQL | 000 = 000 = 000 |
```

### T10. 리뷰 답글

방법은 [`ai-code-review-prompt.md`](../guides/ai-code-review-prompt.md) "작성자가 답하는 법"이다.

```text
수용. <커밋 SHA>에서 갱신 행 수가 0이면 409로 바꿨다. 되돌리면 <테스트 이름>이 실패하는 것을 확인했다.
거부. ADR 0034가 허용한 방향이다. 판단 기록: docs/ai-judgments/<이름>/000.md
보류. 계약 변경이라 ISE-000에서 상대 담당과 정한다.
```

### T11. README 입구

심사위원이 저장소 첫 화면에서 세 곳으로 가게 한다.

```markdown
## 바로 가기
| 보려는 것 | 위치 |
|---|---|
| 서비스 접속 | http://000(프론트), Grafana 000 |
| 발표 자료 | docs/presentation/README.md |
| 설계와 결정 | docs/core/SA MASTER.md, docs/decisions/ |
| 검증 근거 | 판단 기록 docs/ai-judgments/, 측정 docs/design/infra/, 트러블슈팅 docs/troubleshooting.md |
```

## 4. ArchUnit 계층 검사 설계 (ISE-275)

발제 필수 "계층형 아키텍처 검증"을 사람 점검에서 빌드 검사로 올린다. 규칙은 다른 팀 것이 아니라 SA 13장과 ADR에서 온다.

| 규칙 | ArchUnit 표현(요지) | 근거 |
|---|---|---|
| domain은 다른 계층에 의존하지 않는다 | `layeredArchitecture()`에서 Domain은 아무 계층도 접근하지 않음. JPA·`common`은 계층 밖이라 허용 | SA 13장 계층 표 |
| application은 presentation·infrastructure에 의존하지 않는다 | Application은 Domain만 접근 | SA 13장 |
| presentation은 application을 거친다 | Presentation은 Application만 접근(domain 직접 접근은 얼린 목록으로 시작) | SA 13장 |
| infrastructure는 presentation에 의존하지 않는다 | Infrastructure는 Domain·Application만 접근(application의 포트를 구현) | ADR 0043 |
| Kafka 타입은 입구와 출구에만 | `..domain..`·`..application..`은 `org.springframework.kafka..`, `org.apache.kafka..`에 의존하지 않음 | ADR 0043 |
| `reservation`·`concert`는 `seathold`를 import하지 않는다 | reservation-service에서 `..reservation.reservation..`·`..reservation.concert..`이 `..reservation.seathold..`에 의존하지 않음 | ADR 0034(ISE-273으로 위반 0건) |

- **대상**: user-service, reservation-service, payment-service. 서비스마다 테스트 클래스 하나(`ArchitectureTest`), `@AnalyzeClasses(packages = "com.iseonjwa.<서비스>", importOptions = DoNotIncludeTests)`. gateway와 assistant-service는 4계층 대상이 아니라 뺀다.
- **빈 계층**: 모듈마다 네 계층이 다 있지는 않다. `withOptionalLayers(true)`로 둔다.
- **지금 있는 위반**: 처음부터 0건이 아닐 수 있다. `FreezingArchRule.freeze(...)`로 얼려 위반 목록을 저장소(`archunit_store/`)에 남기고 새 위반만 막는다. 얼린 목록은 PR 본문과 [`sa-review/06`](../sa-review/06-layered-architecture-check.md)에 "몇 건, 왜 남겼나"로 적는다. 발표에서는 "규칙을 자동 검사로 올렸고, 남은 위반 N건은 이 이유로 남겼다"가 된다.
- **변이 확인**: 규칙마다 위반 import 하나를 일부러 넣어 실패하는지 본다. 살아남은 변이도 적는다.
- **하지 않는 것**: 모듈 사이 순환 검사(`slices().should().beFreeOfCycles()`)는 넣지 않는다. `reservation → lottery → seathold → reservation` 순환이 ADR 0034가 금지하지 않은 방향으로 남아 있다(ISE-273 "범위 밖").
- **의존성**: `com.tngtech.archunit:archunit-junit5`, 테스트 범위. 버전은 추가할 때 Maven Central에서 확인한다.
