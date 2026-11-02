package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.common.web.RequireRole;
import com.iseonjwa.reservation.lottery.application.LotteryAdminService;
import com.iseonjwa.reservation.lottery.presentation.dto.request.LotteryCreateRequest;
import com.iseonjwa.reservation.lottery.presentation.dto.response.LotteryCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Gateway가 먼저 ADMIN 경로를 막지만, 서비스에서도 @RequireRole로 한 번 더 확인한다
@RestController
@RequireRole(Roles.ADMIN)
@RequiredArgsConstructor
public class AdminLotteryController {

    private final LotteryAdminService lotteryAdminService;

    // 추첨 설정. 같은 트랜잭션에서 추첨석을 LOTTERY 등급으로 뗀다
    @PostMapping("/api/v1/admin/schedules/{scheduleId}/lotteries")
    public ResponseEntity<SuccessResponse<LotteryCreateResponse>> createLottery(
            @PathVariable Long scheduleId, @Valid @RequestBody LotteryCreateRequest request) {
        var result = lotteryAdminService.create(request.toCommand(scheduleId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(201, "추첨을 설정했습니다",
                        LotteryCreateResponse.from(result), MDC.get("traceId")));
    }
}
