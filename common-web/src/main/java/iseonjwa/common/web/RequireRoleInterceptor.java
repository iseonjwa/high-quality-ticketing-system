package iseonjwa.common.web;

import iseonjwa.common.response.BusinessException;
import iseonjwa.common.response.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

// @RequireRole이 붙은 핸들러를 호출하기 전에 사용자 역할을 확인한다.
// 역할 속성이 없으면 401, 허용한 역할이 아니면 403이다. 메서드의 선언이 클래스보다 우선한다.
public class RequireRoleInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        var required = findAnnotation(method);
        if (required == null) {
            return true;
        }

        var role = request.getAttribute(GatewayIdentityFilter.USER_ROLE_ATTRIBUTE);
        if (role == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (!Arrays.asList(required.value()).contains(role)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }

    private RequireRole findAnnotation(HandlerMethod method) {
        var onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
    }
}
