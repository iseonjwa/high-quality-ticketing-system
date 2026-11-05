package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.common.web.RequireRole;
import com.iseonjwa.reservation.lottery.application.LotteryAdminService;
import com.iseonjwa.reservation.lottery.application.LotteryResult;
import com.iseonjwa.reservation.lottery.presentation.dto.request.LotteryCreateRequest;
import com.iseonjwa.reservation.lottery.presentation.dto.request.LotteryUpdateRequest;
import com.iseonjwa.reservation.lottery.presentation.dto.response.LotteryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Gateway가 먼저 ADMIN 경로를 막지만, 서비스에서도 @RequireRole로 한 번 더 확인한다
@RestController
@RequestMapping("/api/v1/admin")
@RequireRole(Roles.ADMIN)
@RequiredArgsConstructor
public class AdminLotteryController {

    private final LotteryAdminService lotteryAdminService;

    // 같은 트랜잭션에서 추첨석을 LOTTERY 등급으로 떼며 추첨을 설정한다
    @PostMapping("/schedules/{scheduleId}/lotteries")
    public ResponseEntity<SuccessResponse<LotteryResponse>> createLottery(@PathVariable Long scheduleId,
                                                                          @Valid @RequestBody LotteryCreateRequest request) {
        LotteryResult lotteryResult = lotteryAdminService.create(request.toCommand(scheduleId));

        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(
                201,
                "추첨을 설정했습니다",
                LotteryResponse.from(lotteryResult),
                MDC.get("traceId")));
    }

    // 일반 오픈·응모 시작 전만 받고, 좌석 수·등급이 바뀌면 추첨석도 함께 고친다
    @PatchMapping("/lotteries/{lotteryId}")
    public ResponseEntity<SuccessResponse<LotteryResponse>> updateLottery(@PathVariable Long lotteryId,
                                                                          @Valid @RequestBody LotteryUpdateRequest request) {
        LotteryResult lotteryResult = lotteryAdminService.update(request.toCommand(lotteryId));

        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "추첨 설정을 수정했습니다",
                LotteryResponse.from(lotteryResult),
                MDC.get("traceId")));
    }
}
