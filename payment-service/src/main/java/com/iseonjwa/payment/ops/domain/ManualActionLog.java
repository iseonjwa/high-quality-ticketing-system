package com.iseonjwa.payment.ops.domain;

import java.time.Instant;

/**
 * Ops 명령 {@code --apply} 시도 한 건의 감사 기록(data-model.md 9절). 한 번 쓰면 바꾸지 않는다.
 *
 * @param fromStatus  적용했으면 전이의 앞 상태. 거부면 비워 둔다
 * @param toStatus    적용했으면 전이의 뒤 상태. 거부면 비워 둔다
 * @param planHash    다시 만든 계획의 해시. 계획을 만들 수 없었으면 사용자가 준 확인 값, 그것도 형식이 틀리면 64자 0
 * @param pgEvidence  실행 시점의 PG 조회 결과 요약(JSON). 카드 정보·비밀값은 넣지 않는다
 * @param externalRef 환불 {@code FAILED}·콘솔 환불의 외부 조치 참조
 * @param approver    4-eyes 명령의 승인자. 실행자와 같으면 DB가 거부하므로 거부 기록에도 넣지 않는다
 * @param reason      운영자 메모. 거부면 거부 사유를 함께 적는다
 */
public record ManualActionLog(String command, OpsTargetType targetType, long targetId, String fromStatus,
        String toStatus, ManualActionResult result, String planHash, String pgEvidence, String externalRef,
        String operator, String approver, String reason, Instant performedAt) {
}
