package com.iseonjwa.reservation.queue.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iseonjwa.reservation.queue.domain.AdmissionStatus;

// 진입·순번 조회에서 공통으로 사용
// status == AdmissionStatus.WAITING인 경우에만 position, estimatedWaitSeconds, nextPollAfterMillis가 null이 아님
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueuePositionResult(AdmissionStatus status,
                                  Long position,
                                  Long estimatedWaitSeconds,
                                  Long nextPollAfterMillis) {

    public static QueuePositionResult ready() {
        return new QueuePositionResult(AdmissionStatus.READY, null, null, null);
    }

    public static QueuePositionResult waiting(Long position, Long estimatedWaitSeconds, Long nextPollAfterMillis) {
        return new QueuePositionResult(AdmissionStatus.WAITING, position, estimatedWaitSeconds, nextPollAfterMillis);
    }
}
