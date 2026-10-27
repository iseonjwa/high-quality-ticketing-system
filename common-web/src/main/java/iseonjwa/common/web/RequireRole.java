package iseonjwa.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 메서드나 클래스에 붙여 허용할 역할을 선언한다. 값은 Roles의 상수를 쓴다.
// 인증이 필요한지는 Gateway가 정하고, 이 어노테이션은 서비스 계층의 역할 확인이다.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    String[] value();
}
