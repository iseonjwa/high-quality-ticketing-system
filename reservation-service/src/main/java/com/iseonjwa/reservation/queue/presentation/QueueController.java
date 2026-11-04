package com.iseonjwa.reservation.queue.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.queue.application.QueueEnterResult;
import com.iseonjwa.reservation.queue.application.QueuePositionResult;
import com.iseonjwa.reservation.queue.application.QueueService;
import com.iseonjwa.reservation.queue.application.QueueTokenResult;
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
                    .body(SuccessResponse.of(
                            201,
                            "대기열에 등록되었습니다",
                            queueEnterResult.queuePositionResult(),
                            MDC.get("traceId")));
        }
        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "이미 대기열에 진입했습니다",
                queueEnterResult.queuePositionResult(),
                MDC.get("traceId")));
    }

    @GetMapping("/me")
    public ResponseEntity<SuccessResponse<QueuePositionResult>> position(@PathVariable Long scheduleId,
                                                                         @RequestAttribute Long currentUserId) {
        QueuePositionResult queuePositionResult = queueService.position(scheduleId, currentUserId);
        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "대기 상태를 조회했습니다",
                queuePositionResult,
                MDC.get("traceId")));
    }

    @PostMapping("/token")
    public ResponseEntity<SuccessResponse<QueueTokenResult>> issueToken(@PathVariable Long scheduleId,
                                                         @RequestAttribute Long currentUserId) {
        QueueTokenResult queueTokenResult = queueService.issueToken(scheduleId, currentUserId);
        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "입장 토큰을 발급했습니다",
                queueTokenResult,
                MDC.get("traceId")));
    }
}
