package com.iseonjwa.common.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderAuditorAwareTest {

    private final HeaderAuditorAware auditorAware = new HeaderAuditorAware();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 요청의_X_User_Id를_감사자로_쓴다() {
        bindRequestWithUserId("42");

        assertThat(auditorAware.getCurrentAuditor()).contains(42L);
    }

    @Test
    void 요청_밖에서는_비워서_시스템으로_남긴다() {
        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }

    @Test
    void 숫자가_아닌_값은_무시한다() {
        bindRequestWithUserId("not-a-number");

        assertThat(auditorAware.getCurrentAuditor()).isEmpty();
    }

    private void bindRequestWithUserId(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderAuditorAware.USER_ID_HEADER, userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
