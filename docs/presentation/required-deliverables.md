# 발제 필수 산출물 점검과 남은 일

발제문(Notion 「발제문 원본」)이 요구하는 산출물을 항목별로 점검하고, 비어 있는 것을 팀원 모두가 나눠 채우도록 정리한다. 발표 자료 제출은 11/18, 발표는 11/19다. 심사위원은 제출 자료로 질문을 미리 쓴다.

- 각자 1절 표에서 자기 영역 줄을 보고, 3절의 작업 키트로 채운다. 담당은 마일스톤 M4 역할 기준 제안이다. 팀이 바꾸면 이 표와 이슈를 고친다.
- 팀원별 이슈(2026-11-13, 마감 11/17): 권우 ISE-266, 영은 ISE-268, 강민 ISE-269, 효승 ISE-270, 준현 ISE-272. 산출물별 이슈: ISE-264(Synthetic 측정, 영은, 11/16), ISE-265(Kafka-UI, 권우, 11/16), ISE-267(보상 캡처, 효승, 11/16), ISE-18(접수형 비교, 권우). 팀이 정할 것: ISE-271(PR마다 AI 리뷰를 남길지, 11/14. CodeRabbit은 넣지 않기로 함). 다른 조 저장소에서 형식을 가져와 새로 올린 일: ISE-274(CI 중복 빌드 취소, PR #597), ISE-275(ArchUnit 계층 검사), ISE-276(도입하지 않은 것·알려진 한계 한 장). 형식 견본은 [`evidence-formats.md`](evidence-formats.md)다.
- 쓰는 도구가 달라도 된다. Claude Code·codex 같은 CLI 에이전트, Claude 채팅(웹·앱), 터미널만 쓰는 경우를 모두 2절에 적었다.
- 결과는 어느 도구로 만들었든 저장소 `docs/`에 PR로 남긴다(AGENTS.md 대전제 4).

## 1. 산출물 표

기준: 2026-11-13 `dev`와 열린 PR #580(AI 코드 검토 방법·대표 사례 가안), #583(Synthetic Data·A0).

| 발제 항목 | 산출물 | 상태 | 어디 | 남은 일 | 제안 담당 |
|---|---|---|---|---|---|
| 실행 환경 | `docker-compose.yml` 커밋, `.env.example`에서 `.env` 만들고 한 번에 기동, README에 기동·`.env` 방법·접속 주소 | 있음. 접속 주소는 README가 아니라 [`workspace.md`](../guides/workspace.md)에 있다 | `README.md`, `.env.example`, `docs/guides/workspace.md` | README에 접속 주소 표 또는 링크 한 줄 | 준현(ISE-272) |
| 분산 트랜잭션(SAGA) | 대상 트랜잭션과 실패 시나리오 문서 | 있음 | SA 8장, [`failure-scenarios.md`](../design/payment/failure-scenarios.md) | 발표 한 장 | 효승·준현(ISE-267) |
| | 방식 선택 근거 한 줄 | 있음(Choreography, SA 14장 #3) | SA 14장 | 없음 | — |
| | 이벤트 흐름도 | 있음(SA 8-1 일반 예매 시퀀스, 8-2 상태 머신). 발표 폴더에는 사용자 흐름도만 있고 예매↔결제 이벤트·보상 흐름 그림은 없다 | SA 8장, [`architecture/`](architecture/README.md) | 보상 경로가 보이는 이벤트 흐름 그림 한 장 | 효승(ISE-267) |
| | 실패 → 보상이 동작하는 로그·화면 캡처 | **없음** | — | ISE-267 | 효승(ISE-267), 결제 쪽 준현 |
| 대용량 트래픽 | 측정 환경 기록표 | 있음 | [Gateway 발표 소스](2026-11-12-gateway-overload-source.md), [`open-peak-scenarios.md`](../design/infra/open-peak-scenarios.md) | 없음 | — |
| | 동시 100명 · 1초 안 시작 · 각 10회 요약(평균 응답·처리량·성공률) | **없음**. 시나리오 A0는 준비됨(PR #583) | `loadtest/k6/scenarios/a0-assignment-baseline.js` | 동기형 ISE-264, 접수형 ISE-18 | 영은(ISE-264), 권우(ISE-18) |
| | 최적화 전·후 2회 이상, 개선 내역 1~3줄 | 있음(Gateway 상한, 좌석 조회, 좌석 동시성) | 위 문서, `docs/design/reservation/` | `performance/`에 발표 한 장 | 강민(ISE-269), 권우(ISE-266) |
| | API가 Kafka에 넣고 바로 응답, Consumer가 처리 | 구현 있음(접수형 선점, ADR 0057). 측정 없음 | `reservation-service` | ISE-18 | 권우(ISE-18) |
| | Kafka-UI 토픽·컨슈머 그룹 캡처, 파티션 수·키 근거 한 줄 | **없음**. 선점 키 근거는 SA 14장 #5에 있다 | — | ISE-265 | 권우(ISE-265) |
| 모니터링 | Prometheus·Grafana 대시보드 | 있음(배포 Grafana) | `docs/design/infra/observability.md` | `deployment/`에 대시보드 캡처 | 준현(ISE-272) |
| 최소 구현 | 실패 정의 문서와 구현, Kafka 실패·중복 대응, 유실 방지 패턴(Outbox), 부하 테스트 | 있음(Outbox, 처리 이력, DLQ, 멱등키, 대사) | `failure-scenarios.md`, ADR, TS | `considerations/`에 발표 한 장 | 영역별(발표 준비 11/16~) |
| AI 설계 검증 로그 | 검증 항목 3개 이상, 거부 1개 이상, 4계층 검증 | 있음(SA 14장 10건, #7이 4계층) | SA 14장 | 발제 양식의 **"AI 프롬프트(요약)" 열이 없다**. 항목마다 판단자가 채운다 | 4·7번 강민(ISE-269), 5번 권우(ISE-266), 10번 영은(ISE-268), 나머지 준현(ISE-272) |
| AI 대표 사례 | 2건 이상, 사례마다 다섯 가지 | 가안 2건(PR #580) | [`ai-verification/`](ai-verification/README.md) | 판단자 확인 | 강민(ISE-269), 권우(ISE-266) |
| Synthetic Data | LLM 프롬프트 원문 | 있음(PR #583) | `loadtest/synthetic/o1-prompt.md`, `o1-response.md` | 없음 | — |
| | 생성 CSV | 있음(PR #583) | `loadtest/k6/data/o1-synthetic-3000.csv` | 없음 | — |
| | 일반 부하 결과와 Synthetic 결과 비교 | **없음** | — | ISE-264 | 영은(ISE-264) |
| 도전: RAG 상담 | 아키텍처 문서, 청킹·임베딩 근거, (선택) 정확도 평가 | 일부. 벡터 DB 대신 키워드 검색을 골랐다(ADR 0049). 테스트 질문 5개 이상과 정확도 평가는 확인 못 함 | [`assistant/`](../design/assistant/README.md), ADR 0049 | 테스트 질문 5개 결과 | 준현(ISE-272) |
| 판단 기록 | 대표 사례·검증 로그의 원천 | 있음(공통 표 170건) | [`ai-judgments/`](../ai-judgments/README.md) | 검증 칸 늦은 기록 갱신, 효승 기록 | 모두(팀원별 이슈) |

## 2. 도구별로 일하는 법

| 도구 | 할 수 있는 것 | 이렇게 쓴다 |
|---|---|---|
| CLI 에이전트(Claude Code, codex) | 저장소를 읽고 명령을 실행한다. `AGENTS.md`를 읽어 팀 규칙과 기록을 따른다 | 저장소 루트(`dev` 최신)에서 세션을 열고 3절의 **CLI** 지시문을 붙여 넣는다 |
| Claude 채팅(웹·앱) | 저장소를 직접 읽지 못하고 명령을 실행하지 못한다. 팀 규칙도 모른다 | 3절의 **채팅** 지시문과 함께 "첨부" 목록의 파일을 올린다. 명령은 채팅이 알려 주면 내가 터미널에서 실행하고 결과를 다시 붙여 넣는다. 채팅이 만든 문서는 내가 정한 경로에 저장해 PR로 올린다 |
| 터미널만 | 명령을 직접 실행한다 | 3절의 **명령**을 그대로 실행하고 결과를 문서에 옮긴다 |

채팅에 첨부할 파일 구하는 법: 저장소가 공개라 GitHub에서 `https://github.com/iseonjwa/high-quality-ticketing-system/blob/dev/<경로>`를 열고 Raw 내려받기를 하거나, 내 PC의 저장소 폴더에서 파일을 끌어다 놓는다. 링크만 주면 채팅이 읽지 못할 때가 있으니 첨부가 확실하다. 채팅에는 항상 `AGENTS.md`와 [`docs/guides/writing.md`](../guides/writing.md)를 같이 올려 문체와 기록 규칙을 알려 준다. 비밀값(`.env`, 토큰, 비밀번호)은 어떤 채팅에도 붙여 넣지 않는다.

## 3. 작업 키트

### K1. 균일 O1·Synthetic O1·A0 측정 (ISE-264, 영은)
- **명령**: 실행 순서는 [`synthetic-data.md`](../design/infra/synthetic-data.md) 5-1절, 명령은 `loadtest/k6/README.md` "배포 서버 측정"의 O1·A0다. 약 70분. 측정 모드 켜고 끄기와 측정 키 옮기기는 본인이 한다([`loadgen.md`](../guides/loadgen.md) 4·5절).
- **CLI**:
  ```text
  Linear ISE-264를 진행해 줘. 실행 순서는 docs/design/infra/synthetic-data.md 5-1절, 명령은 loadtest/k6/README.md "배포 서버 측정"의 O1·A0다. 측정 모드 켜고 끄기와 측정 키 옮기기는 내가 직접 하니 명령만 안내해 줘(docs/guides/loadgen.md 8절). 결과는 compare.py·a0-report.py 표로 synthetic-data.md 6절에 측정 환경 기록표와 함께 적고, 발표용 한 장은 docs/presentation/performance/에 둬.
  ```
- **채팅**(첨부: `docs/design/infra/synthetic-data.md`, `loadtest/k6/README.md`, `docs/guides/loadgen.md`, `AGENTS.md`, `docs/guides/writing.md`):
  ```text
  첨부한 문서대로 배포 서버에서 부하 측정을 하려고 한다. synthetic-data.md 5-1절 순서를 한 단계씩 안내해 줘. 나는 각 단계의 명령을 터미널에서 실행하고 출력을 붙여 넣겠다. 비밀값은 붙여 넣지 않는다. 끝나면 내가 붙여 넣을 compare.py와 a0-report.py 출력으로 synthetic-data.md 6절(결과, 측정 환경 기록표, LLM 예측이 맞았는지)을 마크다운으로 써 줘. 문체는 writing.md를 따른다.
  ```

### K2. 결제 거절 → 보상(ISE-267, 효승), Kafka-UI 캡처와 파티션 근거(ISE-265, 권우)
- **명령**: 1절은 운영자 화면 시연 장애 패널([`demo-scenarios.md`](../guides/demo-scenarios.md))과 사용자 화면에서 한다. 2절 Kafka-UI는 SSH 터널을 연다([`deploy-server-access.md`](../guides/deploy-server-access.md) 3절, `localhost:8085`).
- **CLI**:
  ```text
  Linear ISE-267(결제 거절 → 보상)이나 ISE-265(Kafka-UI)를 진행해 줘. 화면 조작과 캡처는 내가 하고, 너는 순서 안내, 캡처 파일 이름과 위치 정리, 토픽별 파티션 수·키·근거 표 작성을 맡아 줘. 파티션 근거는 ADR 0056, config-repo/payment-service.yml, PaymentCommandProperties와 해당 설계 문서에서 찾고 없으면 "근거 없음"으로 적어. 측정 모드가 켜져 있으면 보상 시연은 하지 않는다.
  ```
- **채팅**(첨부: `docs/guides/demo-scenarios.md`, `docs/decisions/0056-async-seat-hold-key-for-mixed-sections.md`, `config-repo/payment-service.yml`, Kafka-UI 토픽 목록 캡처, `AGENTS.md`, `docs/guides/writing.md`):
  ```text
  첨부한 Kafka-UI 토픽 목록 화면과 문서를 보고, 토픽마다 파티션 수, 메시지 키, 그 수와 키를 고른 이유 한 줄을 표로 써 줘. 이유가 첨부 문서에 없으면 지어내지 말고 "근거 없음"으로 적어. 그리고 결제 거절 뒤 좌석이 풀리는 흐름을 발표 한 장(문제, 흐름, 화면 캡처 자리, 정본 링크)으로 써 줘.
  ```

### K3. 접수형 선점 A0 (ISE-18, 권우)
- **명령**: 동기형은 K1의 A0와 같다. 접수형은 `measure-mode.sh on loadtest/k6/compose.seat-hold-async.yml`(없으면 ISE-18 댓글대로 만든다) 뒤 새 회차로 같은 A0를 돌리고 `a0-report.py`에 두 요약을 같이 준다. 접수형 수치는 202 접수 응답 시간이므로 접수 → 처리 완료(컨슈머 랙)는 Grafana에서 따로 적는다(SA 3장 "오류 정의").
- **CLI**:
  ```text
  ISE-18을 진행하면서 발제 기준선 A0(loadtest/k6/scenarios/a0-assignment-baseline.js, 100명 × 10회)를 동기형과 접수형으로 한 번씩 재 줘. 측정 순서는 docs/guides/loadgen.md 4~5절, A0 명령은 loadtest/k6/README.md다. 측정 모드와 키 옮기기는 내가 한다. 결과는 a0-report.py 표와 측정 환경 기록표로 docs/design/reservation/에, 발표 한 장은 docs/presentation/performance/에 둬. 접수형 측정 중 ISE-265(Kafka-UI 캡처)도 같이 하자.
  ```
- **채팅**(첨부: `loadtest/k6/README.md`, `docs/guides/loadgen.md`, `docs/decisions/0057-async-seat-hold-intake-and-mode.md`, `AGENTS.md`, `docs/guides/writing.md`): K1 채팅 지시문에서 "synthetic-data.md 5-1절" 대신 "A0를 동기형·접수형으로 한 번씩"으로 바꿔 쓴다.

### K4. AI 대표 사례 확인 (강민·권우)
- **CLI**:
  ```text
  docs/presentation/ai-verification/의 <내 사례 파일>은 내 판단 기록으로 만든 발표 사례 가안이다. 연결된 판단 기록·트러블슈팅·PR과 숫자와 서술을 대조해 틀린 곳을 고치고, 고친 이유를 PR 본문에 적어 줘. 발제 다섯 가지(작업, Claude Code 활용 방식, 채택·수정·기각 이유, 빌드·테스트·API 검증 결과, 팀의 MSA 설계 판단)가 다 들어 있는지도 봐 줘.
  ```
- **채팅**(첨부: 사례 파일, 그 파일이 링크한 판단 기록, `AGENTS.md`, `docs/guides/writing.md`): 위 지시문의 첫 문장을 "첨부한 사례 파일은 첨부한 판단 기록으로 만든 가안이다"로 바꾸고, 결과를 고친 사례 파일 전체로 달라고 한다.

### K5. 내 판단 기록 보강 (모두)
- 할 일: (1) 내 기록 중 `검증` 칸이 실제보다 늦은 것(구현·테스트·측정을 끝냈는데 "구현 전"으로 남은 것)을 고친다. (2) 발표에 쓸 만한 판단인데 기록이 없으면 새로 쓴다. 알려진 것: 영은 10/29 대기열 근거 정정, 강민 11/3 입구 어댑터 이동(PR #272)·11/11 F1 실행 결정, 효승 PR #282 DLQ 리뷰 반영과 ADR 0054.
- **CLI**:
  ```text
  docs/ai-judgments/<내 이름>/에서 검증 칸이 실제 상태보다 늦은 기록을 찾아, 연결된 PR·테스트·측정 결과로 검증 칸과 판단 이력을 고쳐 줘. 확인하려고 테스트를 돌려야 하면 돌리고 결과를 적어. 발표 후보로 쓸 만한데 기록이 없는 판단이 있으면 목록으로 먼저 보여 줘.
  ```
- **채팅**(첨부: `.agents/skills/ai-judgment/SKILL.md`, 고칠 기록 파일, 관련 PR 본문이나 테스트 결과, `AGENTS.md`, `docs/guides/writing.md`):
  ```text
  첨부한 SKILL.md 형식을 따르는 판단 기록이다. 첨부한 PR·테스트 결과를 근거로 "검증"과 "판단 이력" 칸을 고친 기록 전체를 마크다운으로 줘. 근거에 없는 내용은 더하지 마. 새 기록이 필요하면 같은 형식으로 쓰고, 근거 칸에는 내가 한 말만 쓴다.
  ```

### K6. AI 설계 검증 로그의 "AI 프롬프트(요약)" 열 (항목별 판단자)
- SA 14장 표에 발제 양식의 "AI 프롬프트(요약)" 열을 더하고, 항목마다 무엇을 물었는지 한 줄을 채운다. 원천은 각 판단 기록의 "맥락 > AI 활용 방식"과 [`sa-review/`](../sa-review/)다. SA는 팀 합의 문서라 PR로 바꾸고 팀이 확인한다.
- **CLI**:
  ```text
  docs/core/SA MASTER.md 14장 표에 "AI 프롬프트 (요약)" 열을 "검증 항목" 다음에 더해 줘(발제 양식: 검증 항목 | AI 프롬프트 | AI 피드백 | 수용 여부 | 판단 근거). 항목마다 판단 기록의 "AI 활용 방식"이나 docs/sa-review/ 원문에서 실제로 물은 내용을 한 줄로 옮기고, 원천을 못 찾으면 "원문 없음"으로 둬. 지어내지 마.
  ```
- **채팅**(첨부: SA 14장 표를 복사한 텍스트, 항목별 판단 기록, `docs/sa-review/01-sa-1st-review.md`): 위 지시문과 같다. 결과 표를 받아 SA 14장에 붙이고 PR로 올린다.

### K7. AI 코드 리뷰 (모두, PR마다)
- **Claude Code CLI 또는 codex CLI**: `script/ai-review.sh <PR 번호>`(Claude), `script/ai-review.sh <PR 번호> --engine codex`(codex). 결과는 `build/ai-review/`, `--post`면 PR 코멘트. 기준은 [`ai-code-review.md`](../guides/ai-code-review.md).
- **채팅**: PR 페이지 주소 끝에 `.diff`를 붙여(`https://github.com/iseonjwa/high-quality-ticketing-system/pull/<번호>.diff`) 내려받고, [`ai-code-review-prompt.md`](../guides/ai-code-review-prompt.md)와 함께 첨부한다. 지시문은 그 파일의 "프롬프트" 절을 그대로 붙여 넣는다. 채팅은 주변 코드를 못 읽으니, 지적이 "주변 코드 확인 필요"로 나오면 해당 파일을 더 첨부한다.
- 어느 쪽이든 지적마다 PR 답글로 수용·거부를 달고, 거부는 판단 기록으로 남긴다.
