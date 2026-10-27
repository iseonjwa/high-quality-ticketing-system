package iseonjwa.common.web;

import iseonjwa.common.response.ApiResponse;
import iseonjwa.common.response.BusinessException;
import iseonjwa.common.response.CommonErrorCode;
import iseonjwa.common.response.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(basePackages = "iseonjwa")
public class CommonExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CommonExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
        var error = exception.getErrorCode();
        return ResponseEntity.status(error.status())
                .body(ApiResponse.failure(error, null, MDC.get("traceId")));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldError(error instanceof org.springframework.validation.FieldError fieldError
                        ? fieldError.getField() : error.getObjectName(), error.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED,
                errors, MDC.get("traceId")), headers, status);
    }

    // 사용자 정보가 필요한 컨트롤러에 Gateway의 사용자 헤더가 없으면 인증 실패다.
    // 누락된 @RequestAttribute는 하위 클래스 없이 ServletRequestBindingException 자체로 던져진다.
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.getClass() == ServletRequestBindingException.class) {
            return new ResponseEntity<>(ApiResponse.failure(CommonErrorCode.UNAUTHORIZED, null, MDC.get("traceId")),
                    headers, HttpStatus.UNAUTHORIZED);
        }
        return super.handleServletRequestBindingException(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var error = switch (status.value()) {
            case 400 -> CommonErrorCode.VALIDATION_FAILED;
            case 401 -> CommonErrorCode.UNAUTHORIZED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> CommonErrorCode.RATE_LIMITED;
            case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> null;
        };
        var response = error != null ? ApiResponse.failure(error, null, MDC.get("traceId")) : fallback(status);
        return new ResponseEntity<>(response, headers, status);
    }

    private ApiResponse<Object> fallback(HttpStatusCode status) {
        var serverError = status.is5xxServerError();
        var code = serverError ? "INTERNAL_SERVER_ERROR" : "REQUEST_FAILED";
        var message = serverError ? "서버 오류가 발생했습니다" : "요청을 처리할 수 없습니다";
        return new ApiResponse<>(status.value(), code, message, null, null, MDC.get("traceId"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
        log.error("처리하지 못한 요청 예외", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_SERVER_ERROR, null, MDC.get("traceId")));
    }
}
