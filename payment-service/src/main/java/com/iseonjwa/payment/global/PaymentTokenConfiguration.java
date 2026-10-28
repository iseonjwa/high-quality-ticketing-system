package com.iseonjwa.payment.global;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PaymentTokenConfiguration {
    // Note: 값이 없을 때도 기본값 ""로 받아 PaymentTokenKey가 조건별 메시지로 기동을 막게 한다.
    // @ConfigurationProperties 바인딩은 실패 메시지에 값을 싣는 경우가 있어 쓰지 않는다.
    @Bean
    PaymentTokenKey paymentTokenKey(@Value("${payment.token.secret:}") String secret) {
        return PaymentTokenKey.from(secret);
    }
}
