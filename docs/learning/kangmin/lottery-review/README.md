# lottery 코드 리뷰 가이드 (이슈·커밋 순서)

kangmin이 맡은 추첨(lottery) 모듈을 처리한 이슈 순서대로 다시 읽으며 이해를 쌓는 계획이다. 한 챕터가 이슈 하나(또는 묶음)이고, 한 번에 한 챕터만 본다. 챕터를 마치면 아래 표의 상태와 노트 칸을 고친다.

## 한 챕터를 보는 순서
1. 이슈 목적: Linear 이슈와 PR 본문에서 "무엇을, 왜"를 한 줄로 적는다.
2. 그때의 변경: `git show --stat <커밋>`으로 파일 목록을 보고, `git show <커밋> -- <파일>`로 의도를 읽는다.
3. 지금의 코드: 이후 리팩터링(ISE-166 입구 이동, ISE-190 domain 분리)으로 위치가 바뀌었으니 현재 파일을 같이 읽는다. "그때 diff = 의도, 지금 코드 = 결과"로 나눠 본다.
4. 테스트로 동작 확인: 그 챕터의 테스트를 돌리고, 한 줄을 일부러 틀리게 바꿔 실패하는지 본다(변이 테스트).
5. 지금 눈으로 리뷰: 이름·책임·실패 의미·동시성에서 고칠 점을 적는다. 바로 고치지 않고 후보로만 남긴다.
6. 기록: 새로 이해한 것은 `qna/` 노트, 고칠 점은 이 표의 "리뷰 후보" 칸.

## 챕터 지도
| 부 | 장 | 이슈 | 커밋 | 주제 | 핵심 파일(지금 위치) | 상태 |
|---|---|---|---|---|---|---|
| 0 기초 | 0 | — | — | 계층·인터페이스 세 종류 | `*/package-info.java` | 완료 → [노트](../qna/2026-11-13-lottery-layers-and-setup-flow.md) |
| 1 설정·응모 | 1 | ISE-84 | `aa0b39f4` | 순위·배정 계산(순수 도메인 정책) | `domain/policy/DrawRanker`, `SeatAllocator`, `DrawEntrant`, `LotterySeat` | 예정 |
| | 2 | ISE-85 | `3588712a` | 추첨 테이블, 설정·수정 API, 추첨석 전환 | `Lottery`, `LotteryAdminService`, `LotterySeatPort`, `AdminLotteryController` | 완료 → [노트](../qna/2026-11-13-lottery-layers-and-setup-flow.md) |
| | 3 | ISE-86 | `82ae2035` | 응모·취소·결과·공개 조회, 멱등키, 조건부 SQL | `LotteryEntryService`, `LotteryEntryRepository`, `LotteryController` | 예정 |
| 2 추첨 실행 | 4 | ISE-87 | `a37466b9` | 마감·추첨 스케줄러, `markDrawn` 재실행 방지, `lock_timeout` | `LotteryDrawService`, `LotteryDrawScheduler`, `SpringDataLotteryRepository` | 예정 |
| 3 당첨 → 결제 | 5 | ISE-88 | `d695f4b5` | 시스템 선점 포트, 결제 결과 처리기 인터페이스 | `SystemHoldPort`, `LotteryPaymentResultHandler` | 예정 |
| | 6 | ISE-90 | `d9d34967` | 당첨 배정 워커, 결제 결과 처리 | `LotteryAssignmentService`, `LotteryPaymentResultService` | 예정 |
| | 7 | ISE-147, ISE-92 | `4f196432`, `3a06bcff` | outbox로 자동결제 요청을 배정과 같은 트랜잭션에 발행 | `BillingPaymentRequestPublisher` | 예정 |
| | 8 | ISE-149 | `4cf0adb4` | 선점 만료와 추첨 처리기 연결, 결과 미수신 1회 연장 | `LotteryHoldExpiryAdapter` | 예정 |
| | 9 | ISE-150 | `32c97093` | `payment.result` 소비자에 추첨 처리기 연결 | Kafka 소비자(presentation) | 예정 |
| 4 승계·종결 | 10 | ISE-93 | `88bfeb94` | 예비자 승계, `FOR UPDATE SKIP LOCKED`, 승계 마감 30분 | `LotterySuccessionService`, `SuccessionPlanner` | 예정 |
| | 11 | ISE-153, ISE-154 | `13d63831` | NO_SEAT 재시도와 배정 실패 건너뛰기(버그 수정) | 배정·승계 서비스 | 예정 |
| | 12 | ISE-91 | `a39591c2` | 종결 스케줄러와 남은 추첨석 반환 | `LotteryFinishService`, `revertAllFromLottery` | 예정 |
| 5 품질·운영 | 13 | ISE-166, ISE-184, ISE-190 | `66f99684`, `8502eee0`, `18cc9fd3` | 리팩터링: 입구 어댑터 이동, 이름 정리, domain 하위 패키지 분리 | 패키지 구조 전체 | 예정 |
| | 14 | — | `b6b242d8` | 지표, Grafana 대시보드, 경보 규칙 | `observability/LotteryMetrics` | 예정 |
| | 15 | ISE-200 | `900f83e4` | 추첨석 전환이 LIMIT을 넘던 버그(`IN` → `ANY(ARRAY)`) | concert `SpringDataScheduleSeatRepository` | 예정 |
| | 16 | ISE-227 | `11ca26e1`, `70dad179` | 회차로 추첨 찾기, 응답에 `serverTime` | `ScheduleLotteryController` | 예정 |

순서 메모
- 1장(ISE-84)이 2장보다 먼저인 이유: 순위·배정 계산은 DB·Spring 없이 도는 순수 도메인이라 계층 구조를 익히기에 가장 쉽다.
- 15장은 2장의 SQL을 고친 버그 수정이다. 2장을 다시 떠올리며 "왜 처음엔 못 봤나"를 본다.
- 7·8·9장은 다른 서비스(payment)와의 접점이라 SAGA 흐름도를 하나 그리고 들어간다.

## 리뷰 후보 (고칠 점, 아직 안 고침)
| 장 | 후보 | 근거 | 상태 |
|---|---|---|---|
| 0 | `application/package-info.java`는 "domain 계층만 의존한다"고 쓰지만 `LotteryAdminService`는 concert 모듈의 `ScheduleRepository`를 직접 쓴다. 모듈 규칙 `#14`의 예외를 주석에 적을지 | `LotteryAdminService.java:28` | 후보 |

## 보는 명령
```bash
git log --no-merges --reverse --author=GGangMY --date=short --format='%h %ad %s' -- reservation-service/src/main/java/com/iseonjwa/reservation/lottery
```
