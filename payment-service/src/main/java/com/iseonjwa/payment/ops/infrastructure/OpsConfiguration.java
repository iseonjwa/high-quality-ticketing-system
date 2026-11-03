package com.iseonjwa.payment.ops.infrastructure;

import com.iseonjwa.payment.ops.application.OpsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsProperties.class)
public class OpsConfiguration {
}
