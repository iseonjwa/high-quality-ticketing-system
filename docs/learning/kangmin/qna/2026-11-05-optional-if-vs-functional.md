---
title: Optional 분기를 if로 쓸 때와 map·orElseGet으로 쓸 때 무엇이 다른가
date: 2026-11-05
updated: 2026-11-05
asker: kangmin
level: 기초
tags: [java, optional, readability, intellij, code-style]
---

# Optional 분기를 if로 쓸 때와 map·orElseGet으로 쓸 때 무엇이 다른가

## 질문
IntelliJ 경고대로 `LotteryEntryService.enter`의 `if (insertedId.isPresent())`를 함수형으로 바꾸면 오히려 읽기 어려워 보인다. 두 방식은 어떻게 다른가.

## 짧은 답
이 코드에서 동작은 같다. 분기마다 다른 조회를 하고 이유를 설명할 주석도 필요하므로 `if`를 유지하는 편이 읽기 쉽다. IDE 안내는 대안 제시이며 반드시 따를 규칙이 아니다.

## 자세히

### if 방식
```java
Optional<Long> insertedId = lotteryEntryRepository.insertIfOpen(lotteryId, requestId, userId, quantity);
if (insertedId.isPresent()) {
    return lotteryEntryRepository.findById(insertedId.get()).orElseThrow();
}

// INSERT가 0행이면 같은 멱등키로 동시에 들어온 앞 요청이 방금 커밋했을 수 있어 다시 조회한다
return findPreviousEntry(userId, requestId, lotteryId, quantity)
        .orElseThrow(() -> new BusinessException(rejectReason(userId, lotteryId, quantity)));
```

### 함수형 방식
```java
return insertedId.map(id -> lotteryEntryRepository.findById(id).orElseThrow())
        .orElseGet(() -> findPreviousEntry(userId, requestId, lotteryId, quantity)
                .orElseThrow(() -> new BusinessException(rejectReason(userId, lotteryId, quantity))));
```

값이 있으면 `map`의 람다가 실행되고, 값이 없으면 `orElseGet`의 람다가 실행된다. `findById`가 값을 돌려주거나 예외를 던지므로 `map` 결과가 빈 값이 되는 경우는 없다. 그래서 위 `if` 방식과 분기와 예외가 같다.

### 비교
| 기준 | `if` | `map`·`orElseGet` |
|---|---|---|
| 동작 | 같음 | 같음 |
| 흐름 | 위에서 아래로 단계가 보임 | 한 식 안에 성공과 실패가 중첩됨 |
| 주석 위치 | 분기 바로 위에 둘 수 있음 | 식 중간에 두기 어려움 |
| 디버깅 | 줄 단위로 멈추기 쉬움 | 람다 안에 중단점을 둬야 함 |
| 잘 맞는 경우 | 분기마다 하는 일이 다르고 이유 설명이 필요할 때 | 값 변환 하나와 기본값 처리처럼 짧을 때 |

```java
// 함수형이 잘 맞는 예
return repository.findById(id)
        .map(EntryResponse::from)
        .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
```

### 인자 없는 orElseThrow()는 무엇을 던지는가

이어서 물은 것: `findById(insertedId.get()).orElseThrow()`처럼 던질 예외를 명시하지 않아도 되는가.

된다. 인자 없는 `orElseThrow()`는 값이 없으면 `NoSuchElementException`을 던진다. 비즈니스 예외가 아니므로 공통 예외 처리기의 `handleUnexpectedException`으로 간다. 같은 트랜잭션에서 방금 INSERT한 행은 정상 흐름에서 항상 조회되므로, 없으면 사용자 잘못이 아니라 서버 쪽 이상이다.

| 선택지 | 의미 | 판단 |
|---|---|---|
| `orElseThrow()` | 있어야 할 값이 없는 서버 이상 | 이 위치에 맞음 |
| `new BusinessException(NOT_FOUND)` | 사용자가 찾는 대상이 없음 | 이상 상황을 404로 숨김 |
| `new IllegalStateException("...")` | 서버 이상 + 설명 | 맞지만 여기서는 주석으로 충분 |

## 이 프로젝트에서는
- `docs/guides/code-style.md` 1절은 Stream·`var` 같은 문법을 문맥에서 이해하기 쉬운지로 고르게 한다. 이 경고를 따르는 것을 요구하지 않는다.
- `enter`는 재요청 확인, INSERT, 동시 요청 재조회의 세 단계가 차례로 보여야 하므로 `if`를 유지한다.

## 면접 포인트
- `Optional.get()`이 항상 나쁜 것은 아니다. `isPresent()`로 확인한 뒤 쓰면 안전하다. 함수형 표현은 안전해서가 아니라 더 간결할 때 고른다.
- 정적 분석 경고는 판단 재료다. 동작이 같으면 읽는 사람이 흐름과 이유를 알기 쉬운 쪽을 고른다.

## 참고
- 사용자가 공유한 IntelliJ 화면과 `LotteryEntryService.enter` 코드를 읽어 확인했다. 두 방식을 따로 실행해 비교하지는 않았다.
