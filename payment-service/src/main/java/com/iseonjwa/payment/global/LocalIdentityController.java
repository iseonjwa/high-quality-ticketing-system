package com.iseonjwa.payment.global;

import com.iseonjwa.common.response.SuccessResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Profile("local")
@RestController
public class LocalIdentityController {
    @GetMapping("/api/v1/payments/_system/identity")
    public SuccessResponse<?> identity(@RequestAttribute Long currentUserId, @RequestAttribute String currentUserRole) {
        var identity = Map.of("service", "payment-service", "userId", currentUserId, "role", currentUserRole);
        return SuccessResponse.of(200, "조회되었습니다", identity, MDC.get("traceId"));
    }
}
