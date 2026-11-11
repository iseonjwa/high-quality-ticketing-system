---
title: API 목록과 토큰
date: 2026-11-11
updated: 2026-11-11
asker: KWON-WOO
level: 기초
tags: [api, rest, jwt, idempotency]
---

# API 목록과 토큰

목차: [README](README.md)

경로는 Gateway를 거치는 외부 경로다. 컨트롤러 코드에서 확인했다.

## 1. 토큰 3종

> 비유: 건물 출입증(Access), 놀이기구 탑승권(입장 토큰), 매표소 확인증(결제 토큰). 출입증만으로는 놀이기구를 못 타고, 탑승권만으로는 결제를 못 한다.

| 토큰 | 발급 | 검증 | 수명 | 용도 |
|---|---|---|---|---|
| Access / Refresh | user-service | Gateway (JWT + Redis 블랙리스트) | Refresh는 Redis에서 회전 | 로그인 상태 |
| 입장 토큰 (`X-Entry-Token`) | reservation(대기열) | Gateway | 10분 | 좌석 조회·선점 |
| 결제 토큰 | reservation(선점) | payment | 선점 만료 10초 전까지 | 결제 승인 |

입장 토큰과 결제 토큰은 서명 토큰이라 검증하는 쪽이 발급한 서비스를 부르지 않는다. 서비스 간 호출을 줄여 오픈 순간 부하가 한 서비스로 번지지 않게 한 것이다.

## 2. 멱등키(Idempotency-Key)

선점, 결제, 응모는 `Idempotency-Key` 헤더가 필요하다. 같은 키로 다시 보내면 새로 처리하지 않고 처음 결과를 돌려준다.

> 비유: 엘리베이터 버튼. 열 번 눌러도 엘리베이터는 한 번 온다.

네트워크가 끊겨 응답을 못 받은 사용자가 "결제" 버튼을 다시 눌러도 두 번 결제되지 않는다. 결제는 처리 중인 키로 다시 오면 202와 기존 paymentId를 준다.

## 3. user-service

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | `/api/v1/users` | 회원가입(201) | 공개 |
| POST | `/api/v1/auth/login` | 로그인 | 공개 |
| POST | `/api/v1/auth/refresh` | 토큰 재발급 | 공개 |
| POST | `/api/v1/auth/logout` | 로그아웃, 블랙리스트 등록 | 사용자 |
| POST | `/api/v1/auth/password-change` | 비밀번호 변경(임시 비밀번호 계정용) | 공개 |
| GET | `/api/v1/users/me` | 내 정보 | 사용자 |
| POST | `/api/v1/admin/users/{userId}/password-reset` | 임시 비밀번호 발급 | 관리자 |

## 4. reservation-service

| 메서드 | 경로 | 설명 | 조건 |
|---|---|---|---|
| GET | `/api/v1/concerts` | 공연 목록 | 공개 |
| GET | `/api/v1/concerts/{concertId}` | 공연 상세 | 공개 |
| POST | `/api/v1/schedules/{scheduleId}/queue` | 대기열 진입 | 사용자 |
| GET | `/api/v1/schedules/{scheduleId}/queue/me` | 순번·상태 | 사용자 |
| POST | `/api/v1/schedules/{scheduleId}/queue/token` | 입장 토큰 발급 | 사용자 |
| GET | `/api/v1/schedules/{scheduleId}/seats` | 좌석 조회 | 입장 토큰 |
| POST | `/api/v1/schedules/{scheduleId}/reservations` | 좌석 선점(201) | 입장 토큰 + 멱등키 |
| GET | `/api/v1/reservations/me` | 내 예매 목록 | 사용자 |
| GET | `/api/v1/reservations/{reservationId}` | 예매 상세(결제 토큰·티켓) | 사용자 |
| GET | `/api/v1/lotteries/{lotteryId}` | 추첨 정보 | 공개 |
| POST | `/api/v1/lotteries/{lotteryId}/entries` | 응모(201) | 멱등키 |
| DELETE | `/api/v1/lotteries/{lotteryId}/entries/me` | 응모 취소(마감 전) | 사용자 |
| GET | `/api/v1/lotteries/{lotteryId}/entries/me` | 내 응모 결과 | 사용자 |

관리자 API:

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/admin/concerts` | 공연 등록 |
| POST | `/api/v1/admin/concerts/{concertId}/schedules` | 회차 등록 |
| POST | `/api/v1/admin/schedules/{scheduleId}/seats` | 회차 좌석 생성 |
| POST | `/api/v1/admin/schedules/{scheduleId}/lotteries` | 추첨 설정 |
| PATCH | `/api/v1/admin/lotteries/{lotteryId}` | 추첨 수정 |

내부 API(Gateway가 외부 요청을 404로 막는다):

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/internal/reconciliation/reservations?ids=` | 결제 대사용 예매 조회 |
| GET | `/internal/reconciliation/reservations/changes` | 기간 안에 확정·취소된 예매 |
| GET | `/internal/seat-holds/schedules/{id}/ledger` | 좌석·보유 매수 원장 점검 |
| POST | `/internal/seat-holds/schedules/{id}/ledger/reconcile` | 원장 보정 |

결제 쪽 대사 설정(`config-repo/payment-service.yml`의 `payment.reconciliation.reservation.enabled`)이 `false`라 앞의 두 API는 기본 설정으로는 호출되지 않는다.

## 5. payment-service

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/payments` | 결제 승인. 200(APPROVED·DECLINED) 또는 202(UNKNOWN). 멱등키 필요 |
| POST | `/api/v1/payments/checkout` | 결제창 준비 정보(orderId·금액·클라이언트 키). 저장하지 않음 |
| GET | `/api/v1/payments/{paymentId}` | 결제 조회. 남의 결제는 404 |
| POST | `/api/v1/payments/billing-keys` | 빌링키 등록(201) |
| DELETE | `/api/v1/payments/billing-keys/me` | 빌링키 해지 |

운영 복구는 HTTP API가 아니라 `ops` 프로필 명령행이다: `inspect`, `payment resolve`, `refund retry`, `refund resolve`, `outbox requeue`, `dead-letter resolve`. 기본은 dry-run이고 두 사람 확인(4-eyes)을 거친다. 돈을 바꾸는 작업을 웹 버튼 하나로 열지 않으려는 선택이다.

남의 결제를 403이 아니라 404로 주는 이유: 403은 "그 결제가 존재한다"는 정보를 흘린다.

## 6. assistant-service

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/assistant/messages` | 상담 답변 |
| POST | `/api/v1/assistant/messages/stream` | SSE 스트리밍 답변 |

## 7. mock-pg (내부용, Gateway 미경유)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/mock-pg/payments` | 승인 |
| POST | `/mock-pg/payments/{orderId}/cancel` | 망취소(늦은 승인 방지) |
| GET | `/mock-pg/payments/{orderId}` | 조회 |
| POST / GET | `/mock-pg/refunds`, `/mock-pg/refunds/{refundId}` | 환불, 환불 조회 |
| POST | `/mock-pg/billing-keys` | 빌링키 발급 |
| POST / GET | `/mock-pg/faults` | 장애 주입(test·loadtest 프로필만) |
| GET | `/mock-pg/ledger/*` | 원장 조회(test·loadtest 프로필만) |

관리자 시연 화면은 Gateway 라우트 `/api/v1/admin/demo/pg/faults`, `/api/v1/admin/demo/pg/ledger/summary`로 장애 주입을 건다.

## 8. 공통 응답 형식

모든 서비스가 `common`의 응답 봉투(Success/Failure)와 ErrorCode를 쓴다. 오류는 Accept 헤더와 상관없이 JSON으로 낸다(ISE-221). 대표 오류 코드는 `ENTRY_TOKEN_INVALID`(403), `PAYMENT_ALREADY_CLOSED`(409), `PG_UNAVAILABLE`(503)이다.

## 9. 설계만 있는 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/reservations/{id}/cancel` | 예매 취소 |
| DELETE | `/api/v1/schedules/{id}/queue/me` | 대기 취소 |
| GET | `/api/v1/reservations/requests/{requestId}` | 접수형 선점 결과 조회 |
| GET | `/api/v1/notifications` | 알림함(보류) |

## 면접 포인트

- "멱등키는 어떻게 구현했나" → 키별 처리 결과를 저장하고, 같은 키가 오면 저장한 응답을 돌려준다. 5xx는 저장하지 않아 다시 시도할 수 있게 했다. 결제는 예매당 결제 1건을 DB UNIQUE로도 막는다.
- "토큰을 왜 여러 개로 나눴나" → 권한 범위와 수명이 다르다. 입장 토큰은 회차 하나에 10분만 유효해 새도 피해가 작고, 서명 검증만으로 끝나 대기열 서비스에 부하를 주지 않는다.

## 참고

- `docs/core/SA MASTER.md` 7-3(토큰), 11-4(API)
- `docs/design/payment/api.md`, `docs/design/reservation/contracts.md`
- `http/lottery-e2e.http`: 추첨 E2E 요청 순서
