package com.iseonjwa.payment.billing.presentation;

import com.iseonjwa.common.response.SuccessResponse;
import com.iseonjwa.payment.billing.application.BillingKeyService;
import com.iseonjwa.payment.billing.application.BillingKeyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/billing-keys")
public class BillingKeyController {
    private final BillingKeyService billingKeys;

    public BillingKeyController(BillingKeyService billingKeys) {
        this.billingKeys = billingKeys;
    }

    // 사용자 ID는 Gateway가 검증해 전달한 값만 쓴다(0021). 다시 등록하면 기존 키는 REVOKED가 된다
    @PostMapping
    public ResponseEntity<SuccessResponse<BillingKeyView>> register(@RequestAttribute Long currentUserId,
            @Valid @RequestBody RegisterRequest request) {
        var view = billingKeys.register(currentUserId, request.authKey());
        return ResponseEntity.status(201)
                .body(SuccessResponse.of(201, "빌링키를 등록했습니다", view, MDC.get("traceId")));
    }

    @DeleteMapping("/me")
    public SuccessResponse<Map<String, Boolean>> revoke(@RequestAttribute Long currentUserId) {
        var revoked = billingKeys.revoke(currentUserId);
        return SuccessResponse.of(200, "빌링키를 해지했습니다", Map.of("revoked", revoked), MDC.get("traceId"));
    }

    record RegisterRequest(@NotBlank @Size(max = 300) String authKey) {}
}
