package iseonjwa.payment.global;

import iseonjwa.common.response.ApiResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Profile("local")
@RestController
public class LocalIdentityController {
    @GetMapping("/api/v1/payments/_system/identity")
    public ApiResponse<?> identity(@RequestAttribute Long currentUserId, @RequestAttribute String currentUserRole) {
        return ApiResponse.success(200, Map.of("service", "payment-service", "userId", currentUserId,
                "role", currentUserRole), MDC.get("traceId"));
    }
}
