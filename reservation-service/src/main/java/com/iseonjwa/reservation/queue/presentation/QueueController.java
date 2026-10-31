package com.iseonjwa.reservation.queue.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.queue.application.QueueEnterResult;
import com.iseonjwa.reservation.queue.application.QueuePositionResult;
import com.iseonjwa.reservation.queue.application.QueueService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;


    @PostMapping
    public ResponseEntity<SuccessResponse<QueuePositionResult>> enter(@PathVariable Long scheduleId,
                                                                      @RequestAttribute Long currentUserId) {
        QueueEnterResult queueEnterResult = queueService.enter(scheduleId, currentUserId);

        if (queueEnterResult.joined()) {
            URI location = URI.create("/api/v1/schedules/" + scheduleId + "/queue/me");
            return ResponseEntity.created(location)
                    .body(SuccessResponse.of(201, "대기열에 등록되었습니다", queueEnterResult.queuePositionResult(), MDC.get("traceId")));
        }
        return ResponseEntity.ok(SuccessResponse.of(200, "이미 대기열에 등록되어 있습니다", queueEnterResult.queuePositionResult(), MDC.get("traceId")));
    }
}
