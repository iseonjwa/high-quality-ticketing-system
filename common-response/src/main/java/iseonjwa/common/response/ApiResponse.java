package iseonjwa.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int status, String code, String message, T data,
                             List<FieldError> errors, String traceId) {
    public ApiResponse {
        errors = errors == null ? null : List.copyOf(errors);
    }

    public static <T> ApiResponse<T> success(int status, T data, String traceId) {
        if (status < 200 || status >= 300 || status == 204) {
            throw new IllegalArgumentException("본문을 포함하는 2xx 상태가 필요합니다");
        }
        return new ApiResponse<>(status, "SUCCESS", "성공", data, null, traceId);
    }

    public static ApiResponse<Void> failure(ErrorCode error, List<FieldError> errors, String traceId) {
        return new ApiResponse<>(error.status(), error.code(), error.message(), null, errors, traceId);
    }
}
