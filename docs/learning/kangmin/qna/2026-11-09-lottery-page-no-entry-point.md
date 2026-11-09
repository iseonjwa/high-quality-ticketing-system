---
title: 사용자가 프론트에서 추첨 화면을 찾아 들어갈 수 없는 이유와 고칠 방법
date: 2026-11-09
updated: 2026-11-09
asker: kangmin
level: 중급
tags: [frontend, api-design, lottery, gateway, contract]
---

# 사용자가 프론트에서 추첨 화면을 찾아 들어갈 수 없는 이유와 고칠 방법

## 질문
프론트에서 주소를 직접 치지 않으면 추첨 화면에 못 들어가는 것 같다.

## 짧은 답
- 맞다. 사용자 추첨 화면은 `/lotteries/:lotteryId` 하나이고, 이 주소로 가는 링크가 사용자 화면 어디에도 없다.
- 원인은 백엔드에 "공연·회차로 추첨을 찾는 API"가 없어서다. 프론트 코드 주석이 이 사실을 적어 두었다(`frontend/src/app/paths.ts`의 `lotteryPath`: "운영자가 추첨을 설정하며 받은 이 주소로만 들어온다").
- 홈의 추첨 안내 띠(`ConcertListPage.tsx`의 `LotteryPromo`)도 자동결제 카드 등록(`/me`)으로만 보낸다.

## 자세히

### 1. 지금 있는 추첨 API (reservation-service)
| API | 용도 |
|---|---|
| `GET /api/v1/lotteries/{lotteryId}` | 추첨 공개 조회(로그인 없이, Gateway `PUBLIC_GET`) |
| `POST /api/v1/lotteries/{lotteryId}/entries` | 응모 |
| `GET`·`DELETE /api/v1/lotteries/{lotteryId}/entries/me` | 내 응모 조회·취소 |
| `POST /api/v1/admin/schedules/{scheduleId}/lotteries` | 운영자 추첨 설정 |
| `PATCH /api/v1/admin/lotteries/{lotteryId}` | 운영자 추첨 수정 |

모두 `lotteryId`를 알아야 한다. 회차나 공연에서 `lotteryId`를 얻는 길이 없다.

### 2. 고칠 방법 비교
| 안 | 내용 | 얻는 것 | 대가 |
|---|---|---|---|
| A. 회차별 추첨 조회 | `GET /api/v1/schedules/{scheduleId}/lottery` (없으면 404). 공연 상세 화면이 회차마다 불러 "추첨 응모" 버튼을 보인다 | 추첨 모듈 안에서 끝난다. 공연 API를 안 바꾼다 | 회차 수만큼 요청. Gateway 공개 경로 추가. 프론트 수정은 프론트 담당 |
| B. 공연 상세 응답에 `lotteryId` 추가 | 회차 응답에 추첨 번호(없으면 null) | 요청 한 번 | 공연 모듈(다른 담당)이 추첨을 알게 된다. 계약 변경 |
| C. 열린 추첨 목록 | `GET /api/v1/lotteries?status=OPEN`. 홈 안내 띠에서 목록으로 | 홈에서 바로 찾는다 | 상태 조건·정렬·페이지 설계가 더 든다 |

추천은 A다. 추첨 담당 코드 안에서 끝나고, 공연 모듈 경계를 건드리지 않는다.

### 3. 남은 확인
- 프론트는 프론트 담당(ISE-121·124) 코드라 화면 수정은 contract 이슈로 요청한다.
- Gateway `AccessPolicy.PUBLIC_GET`에 새 경로를 더해야 로그인 없이 보인다(ISE-152와 같은 방식).
