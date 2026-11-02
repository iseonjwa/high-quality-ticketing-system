package com.iseonjwa.reservation.lottery.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.reservation.lottery.application.LotteryEntryService;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.EntryRequest;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.EntryResponse;
import com.iseonjwa.reservation.lottery.presentation.dto.LotteryEntryDtos.LotteryInfoResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 추첨 응모 API(details.md 2절). 응모는 대기열·입장 토큰을 거치지 않는다(D3).
 * 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다. 추첨 정보 조회는 로그인 없이도 응답한다.
 */
@RestController
@RequiredArgsConstructor
public class LotteryController {
    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다(선점 API와 같음)
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final LotteryEntryService lotteryEntryService;

    // 응모. 같은 멱등키로 다시 보내면 처음 응모를 그대로 돌려준다(D3 A안 201)
    @PostMapping("/api/v1/lotteries/{lotteryId}/entries")
    public ResponseEntity<SuccessResponse<EntryResponse>> enter(@RequestAttribute Long currentUserId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @PathVariable Long lotteryId,
            @Valid @RequestBody EntryRequest request) {
        var entry = lotteryEntryService.enter(currentUserId, parseKey(idempotencyKey), lotteryId,
                request.quantity());
        return ResponseEntity.status(201)
                .body(SuccessResponse.of(201, "응모했습니다", EntryResponse.from(entry), MDC.get("traceId")));
    }

    // 응모 취소. 마감 전만 받고, 취소 뒤 같은 추첨에 다시 응모할 수 없다(D5)
    @DeleteMapping("/api/v1/lotteries/{lotteryId}/entries/me")
    public ResponseEntity<SuccessResponse<EntryResponse>> cancel(@RequestAttribute Long currentUserId,
            @PathVariable Long lotteryId) {
        var entry = lotteryEntryService.cancel(currentUserId, lotteryId);
        return ResponseEntity.ok(SuccessResponse.of(200, "응모를 취소했습니다", EntryResponse.from(entry),
                MDC.get("traceId")));
    }

    // 내 응모 결과. 알림(F7) 전까지 결과 안내의 기본 경로다
    @GetMapping("/api/v1/lotteries/{lotteryId}/entries/me")
    public ResponseEntity<SuccessResponse<EntryResponse>> myEntry(@RequestAttribute Long currentUserId,
            @PathVariable Long lotteryId) {
        var entry = lotteryEntryService.myEntry(currentUserId, lotteryId);
        return ResponseEntity.ok(SuccessResponse.of(200, "응모 결과입니다", EntryResponse.from(entry),
                MDC.get("traceId")));
    }

    // 추첨 정보와 응모 규칙(공개)
    @GetMapping("/api/v1/lotteries/{lotteryId}")
    public ResponseEntity<SuccessResponse<LotteryInfoResponse>> lottery(@PathVariable Long lotteryId) {
        var lottery = lotteryEntryService.lottery(lotteryId);
        return ResponseEntity.ok(SuccessResponse.of(200, "추첨 정보입니다", LotteryInfoResponse.from(lottery),
                MDC.get("traceId")));
    }

    private static UUID parseKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return UUID.fromString(value);
    }
}
