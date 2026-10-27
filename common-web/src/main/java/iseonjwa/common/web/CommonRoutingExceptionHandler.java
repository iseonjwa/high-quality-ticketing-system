package iseonjwa.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 매핑 전 오류에는 컨트롤러 패키지가 없어 API 경로로 범위를 제한한다.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommonRoutingExceptionHandler {
    private final CommonExceptionHandler handler;

    public CommonRoutingExceptionHandler(CommonExceptionHandler handler) {
        this.handler = handler;
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, NoHandlerFoundException.class,
            NoResourceFoundException.class})
    public ResponseEntity<Object> handleRoutingException(Exception exception, ServletWebRequest request)
            throws Exception {
        var servletRequest = request.getRequest();
        var path = servletRequest.getRequestURI().substring(servletRequest.getContextPath().length());
        if (!path.startsWith("/api/v1/")) {
            throw exception;
        }
        return handler.handleException(exception, request);
    }
}
