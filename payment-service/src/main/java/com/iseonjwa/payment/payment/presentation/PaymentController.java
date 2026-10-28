package com.iseonjwa.payment.payment.presentation;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.common.response.FailureResponse;
import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.payment.payment.application.ApprovePaymentCommand;
import com.iseonjwa.payment.payment.application.PaymentApprovalService;
import com.iseonjwa.payment.payment.application.PaymentOutcome;
import com.iseonjwa.payment.payment.presentation.PaymentDtos.ApproveRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentApprovalService approval;

    public PaymentController(PaymentApprovalService approval) {
        this.approval = approval;
    }

    // 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다. 본문이나 토큰의 값을 믿지 않는다(0021)
    @PostMapping
    public ResponseEntity<?> approve(@RequestAttribute Long currentUserId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody ApproveRequest request) {
        var outcome = approval.approve(new ApprovePaymentCommand(currentUserId, parseKey(idempotencyKey),
                request.paymentToken(), request.amount()));
        return toResponse(outcome);
    }

    private static UUID parseKey(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
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
