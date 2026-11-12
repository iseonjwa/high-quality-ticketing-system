package com.iseonjwa.reservation.seathold.application;

import java.util.UUID;

/**
 * 동기형 선점의 결과. 보통은 {@link Held}(201)이고, 같은 키의 첫 요청이 접수형으로 들어와 아직 결과를 동기형 응답으로
 * 줄 수 없으면 {@link Accepted}(202)다. 요청은 시작한 방식으로 끝까지 답한다(ADR 0058).
 */
public sealed interface SyncHoldOutcome {
    record Held(SeatHoldResult result) implements SyncHoldOutcome {}

    // 결과는 접수 결과 조회 API(contracts.md 2-2절)로 본다
    record Accepted(UUID requestId) implements SyncHoldOutcome {}
}
