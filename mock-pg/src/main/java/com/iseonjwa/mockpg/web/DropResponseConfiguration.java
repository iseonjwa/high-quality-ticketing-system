package com.iseonjwa.mockpg.web;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DropResponseConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> dropResponseValve() {
        return factory -> factory.addContextValves(new DropResponseValve());
    }
}
