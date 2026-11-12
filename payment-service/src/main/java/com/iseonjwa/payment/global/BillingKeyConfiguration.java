package com.iseonjwa.payment.global;

import com.iseonjwa.payment.billing.application.BillingKeyEncryptor;
import com.iseonjwa.payment.billing.application.BillingKeyRegistrationProperties;
import com.iseonjwa.payment.billing.infrastructure.crypto.AesGcmBillingKeyEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingKeyRegistrationProperties.class)
public class BillingKeyConfiguration {
    // 값이 없을 때도 ""로 받아 AesGcmBillingKeyEncryptor가 조건별 메시지로 기동을 막게 한다(PaymentTokenConfiguration과 같다)
    @Bean
    BillingKeyEncryptor billingKeyEncryptor(@Value("${payment.billing-key.secret:}") String secret) {
        return AesGcmBillingKeyEncryptor.from(secret);
    }
}
