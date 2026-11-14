---
title: 불안정한 테스트(flaky test)란 무엇이고, AdminLotteryApiTest 실패는 무엇 때문이었나
date: 2026-11-02
asker: hyos1
level: 기초
tags: [test, flaky-test, gradle, postgresql, lottery]
---

# 불안정한 테스트(flaky test)란 무엇이고, AdminLotteryApiTest 실패는 무엇 때문이었나

## 질문
ISE-140 작업 중 "불안정한 테스트가 있다"고 했는데, 무슨 상황이고 왜 생기며 어떻게 해결하나?

## 답

### 1. 불안정한 테스트
코드를 바꾸지 않았는데 돌릴 때마다 통과하기도 하고 실패하기도 하는 테스트다. 원인은 보통 테스트 바깥에 있다.
- 여러 테스트가 같은 DB를 함께 쓰는데 남긴 데이터나 실행 순서가 서로 영향을 준다.
- 백그라운드 스케줄러가 테스트 데이터를 건드린다.
- 같은 DB를 쓰는 다른 테스트 실행(IDE, 다른 터미널)이 동시에 돈다.
- 시각(`now()`)이나 DB 실행 계획처럼 매번 달라질 수 있는 값에 결과가 기댄다.

### 2. 이번에 본 것 (11/2, ISE-140 브랜치)
| 실행 | 결과 |
|---|---|
| `verifyWorkspace` 1회차(`findOnSaleBetween`) | 통과 |
| `verifyWorkspace` 2회차(`findOpenBy`로 이름·조건만 바꿈) | `AdminLotteryApiTest` 9개 실패 |
| `AdminLotteryApiTest`만 따로 | 통과 |
| `verifyWorkspace --rerun-tasks`(전부 다시) | 통과 |

실패 내용은 "추첨석을 2개 떼라고 했는데 3개가 LOTTERY가 됐다", "좌석 수를 늘리는 수정이 409(좌석 모자람)" 같은 것이었다. 회차에 S석이 예상보다 먼저 LOTTERY로 바뀌어 있던 모양이다.

### 3. 확인한 것과 모르는 것
- ISE-140 변경은 회차 조회 메서드 하나라 추첨 설정 코드(`convertToLottery`)와 겹치지 않는다.
- 추첨 스케줄러는 `application-test.yml`에서 꺼져 있다(`lottery.scheduler.enabled: false`). 스케줄러가 끼어든 것은 아니다.
- 테스트는 매번 새 회차를 만들고 그 회차 좌석만 센다. 다른 테스트 클래스의 데이터가 섞일 길은 찾지 못했다.
- 추첨석 전환 SQL(`UPDATE ... WHERE id IN (SELECT ... LIMIT n FOR UPDATE)`)이 실행 계획에 따라 n개보다 많이 바꿀 수 있는지 테스트 DB에서 계획을 바꿔 가며 실험했지만 재현되지 않았다.
- 원인은 아직 모른다. 같은 테스트 DB(포트 25435)를 쓰는 다른 테스트 실행이 그 시각에 동시에 돌았는지는 확인하지 못했다.

### 4. 어떻게 다루나
- 이번 PR: 다시 돌려 통과했고 변경이 무관하므로 그대로 진행한다. 실패를 기록해 둔다.
- 다시 나면: `reservation-service/build/test-results/test/TEST-*AdminLotteryApiTest.xml`을 바로 보관한다(다음 실행이 덮어쓴다). 실패 메시지와 그때 다른 테스트 실행이 있었는지를 테스트 작성자(이강민 님)에게 전한다.
- 테스트 DB를 공유하는 동안에는 IDE 테스트와 Gradle 테스트를 동시에 돌리지 않는다.
