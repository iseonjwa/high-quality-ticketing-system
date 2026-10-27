package com.iseonjwa.common.web;

import com.iseonjwa.common.response.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring 기본 {@code /error} 응답을 실패 형태로 바꾼다 (SA 11-2).
 * 컨트롤러에 닿기 전(필터)에서 난 오류가 여기로 온다. 컨트롤러 안의 예외는 {@link GlobalExceptionHandler}가 처리한다.
 */
@RestController
public class CommonErrorController implements ErrorController {

    private final TraceIdProvider traceIdProvider;

    public CommonErrorController(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        int status = statusOf(request);
        ErrorResponse body = ErrorResponse.ofStatus(status, traceIdProvider.currentTraceId());
        return ResponseEntity.status(status).body(body);
    }

    private int statusOf(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return status instanceof Integer code ? code : 500;
    }
}
