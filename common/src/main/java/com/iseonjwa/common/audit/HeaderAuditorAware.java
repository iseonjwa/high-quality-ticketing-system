package com.iseonjwa.common.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.AuditorAware;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Gateway가 붙인 {@code X-User-Id}로 감사 컬럼의 사람(created_by·updated_by)을 채운다 (SA 12-1).
 * HTTP 요청 밖(Kafka 소비자, 스케줄러)이거나 헤더가 없으면 비워 둔다. NULL은 시스템을 뜻한다.
 */
public class HeaderAuditorAware implements AuditorAware<Long> {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public Optional<Long> getCurrentAuditor() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(userId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
