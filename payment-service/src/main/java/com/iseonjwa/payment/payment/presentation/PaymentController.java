package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.payment.payment.application.ApprovePaymentCommand;
import com.iseonjwa.payment.payment.application.PaymentApprovalService;
import com.iseonjwa.payment.payment.application.PaymentDetail;
import com.iseonjwa.payment.payment.application.PaymentOutcome;
import com.iseonjwa.payment.payment.application.PaymentQueryService;
import com.iseonjwa.payment.payment.presentation.PaymentDtos.ApproveRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentApprovalService approval;
    private final PaymentQueryService query;

    public PaymentController(PaymentApprovalService approval, PaymentQueryService query) {
        this.approval = approval;
        this.query = query;
    }

    // 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다. 본문이나 토큰의 값을 믿지 않는다(0021)
    @PostMapping
    public ResponseEntity<?> approve(@RequestAttribute Long currentUserId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody ApproveRequest request) {
        var outcome = approval.approve(new ApprovePaymentCommand(currentUserId, parseKey(idempotencyKey),
                request.paymentToken(), request.amount().longValueExact()));
        return toResponse(outcome);
    }

    // 결제 토큰이 필요 없어 토큰이 만료된 뒤에도 조회할 수 있다. 소유자 검사는 서비스가 맡는다(0021)
    @GetMapping("/{paymentId}")
    public SuccessResponse<PaymentDetail> get(@RequestAttribute Long currentUserId,
            @RequestAttribute String currentUserRole, @PathVariable Long paymentId) {
        return SuccessResponse.of(200, "결제를 조회했습니다", query.find(paymentId, currentUserId, currentUserRole),
                MDC.get("traceId"));
    }

    // UUID.fromString은 "1-1-1-1-1"처럼 자리수가 모자란 값도 받아들여서 표준 형식(8-4-4-4-12)을 먼저 확인한다
    private static final Pattern UUID_FORMAT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static UUID parseKey(String value) {
        if (!UUID_FORMAT.matcher(value).matches()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return UUID.fromString(value);
    }

    private ResponseEntity<?> toResponse(PaymentOutcome outcome) {
        var response = ResponseEntity.status(outcome.httpStatus());
        if (outcome.locationPaymentId() != null) {
            response.header(HttpHeaders.LOCATION, "/api/v1/payments/" + outcome.locationPaymentId());
        }
        if (outcome.isError()) {
            return response.body(FailureResponse.of(outcome.error(), null, MDC.get("traceId")));
        }
        return response.body(SuccessResponse.of(outcome.httpStatus(), message(outcome), outcome.data(),
                MDC.get("traceId")));
    }

    private static String message(PaymentOutcome outcome) {
        return switch (outcome.data().status()) {
            case APPROVED -> "결제가 승인되었습니다";
            case DECLINED -> "결제가 거절되었습니다";
            case REFUNDED -> "환불되었습니다";
            default -> "결제를 확인하고 있습니다";
        };
    }
}
