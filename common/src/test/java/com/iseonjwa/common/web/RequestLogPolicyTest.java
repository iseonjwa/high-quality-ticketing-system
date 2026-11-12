package com.iseonjwa.common.web;

import com.iseonjwa.common.web.RequestLogPolicy.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogPolicyTest {
    private final RequestLogPolicy problems = new RequestLogPolicy(Mode.PROBLEMS, 500, Duration.ofMillis(200));

    @Test
    void ALL은_빠른_정상_응답도_남긴다() {
        var all = new RequestLogPolicy(Mode.ALL, 500, Duration.ofMillis(200));

        assertThat(all.shouldLog(200, false, 1)).isTrue();
    }

    // 경계값: 상태는 min-status부터, 시간은 slow-threshold부터 남긴다
    @ParameterizedTest
    @CsvSource({
            "200, 199, false",
            "200, 200, true",
            "499, 10, false",
            "500, 10, true",
            "503, 0, true",
            "409, 10, false",
            "401, 10, false"
    })
    void PROBLEMS는_5xx와_느린_요청만_남긴다(int status, long millis, boolean expected) {
        assertThat(problems.shouldLog(status, false, millis)).isEqualTo(expected);
    }

    @Test
    void PROBLEMS도_클라이언트가_끊은_요청은_상태와_시간에_관계없이_남긴다() {
        assertThat(problems.shouldLog(200, true, 1)).isTrue();
    }

    @Test
    void min_status를_400으로_내리면_4xx도_남긴다() {
        var withClientErrors = new RequestLogPolicy(Mode.PROBLEMS, 400, Duration.ofMillis(200));

        assertThat(withClientErrors.shouldLog(401, false, 10)).isTrue();
        assertThat(withClientErrors.shouldLog(399, false, 10)).isFalse();
    }
}
