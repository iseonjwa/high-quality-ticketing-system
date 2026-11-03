package com.iseonjwa.payment.ops.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Ops 설정(ADR 0028 결정 7).
 *
 * @param operators 실행자·승인자로 허용하는 팀원 ID 목록. 비밀값이 아니라 config-repo에 둔다
 */
@ConfigurationProperties(prefix = "payment.ops")
public record OpsProperties(@DefaultValue List<String> operators) {
    public OpsProperties {
        operators = List.copyOf(operators);
    }

    public boolean isAllowed(String id) {
        return id != null && operators.contains(id);
    }
}
