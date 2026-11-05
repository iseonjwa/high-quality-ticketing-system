package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.lottery.application.LotteryEntryService;
import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.LotteryEntry;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.EntryRequest;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.EntryResponse;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.LotteryInfoResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 대기열·입장 토큰을 거치지 않는 추첨 응모 API로, 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다(D3, details.md 2절).
 */
@RestController
@RequestMapping("/api/v1/lotteries/{lotteryId}")
@RequiredArgsConstructor
public class LotteryController {
    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다(선점 API와 같음)
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final LotteryEntryService lotteryEntryService;

    // 같은 멱등키로 다시 보내면 처음 응모를 그대로 돌려준다(D3 A안 201)
    @PostMapping("/entries")
    public ResponseEntity<SuccessResponse<EntryResponse>> enter(@RequestAttribute Long currentUserId,
                                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                @PathVariable Long lotteryId,
                                                                @Valid @RequestBody EntryRequest request) {
        LotteryEntry lotteryEntry = lotteryEntryService.enter(
                currentUserId,
                parseIdempotencyKey(idempotencyKey),
                lotteryId,
                request.quantity());

        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(
                201,
                "응모했습니다",
                EntryResponse.from(lotteryEntry),
                MDC.get("traceId")));
    }

    // 마감 전 취소만 받고, 취소 뒤 같은 추첨에 다시 응모할 수 없다(D5)
    @DeleteMapping("/entries/me")
    public ResponseEntity<SuccessResponse<EntryResponse>> cancel(@RequestAttribute Long currentUserId,
                                                                 @PathVariable Long lotteryId) {
        LotteryEntry lotteryEntry = lotteryEntryService.cancel(currentUserId, lotteryId);

        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "응모를 취소했습니다",
                EntryResponse.from(lotteryEntry),
                MDC.get("traceId")));
    }

    // 알림(F7) 전까지 결과 안내의 기본 경로인 내 응모 결과다
    @GetMapping("/entries/me")
    public ResponseEntity<SuccessResponse<EntryResponse>> myEntry(@RequestAttribute Long currentUserId,
                                                                  @PathVariable Long lotteryId) {
        LotteryEntry lotteryEntry = lotteryEntryService.myEntry(currentUserId, lotteryId);

        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "응모 결과입니다",
                EntryResponse.from(lotteryEntry),
                MDC.get("traceId")));
    }

    // 로그인 없이도 응답하는 추첨 정보와 응모 규칙이다
    @GetMapping
    public ResponseEntity<SuccessResponse<LotteryInfoResponse>> lottery(@PathVariable Long lotteryId) {
        Lottery lottery = lotteryEntryService.findLottery(lotteryId);

        return ResponseEntity.ok(SuccessResponse.of(
                200,
                "추첨 정보입니다",
                LotteryInfoResponse.from(lottery),
                MDC.get("traceId")));
    }

    private static UUID parseIdempotencyKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }

        return UUID.fromString(value);
    }
}
