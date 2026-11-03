package com.iseonjwa.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Profiles;

@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(PaymentApplication.class, args);
        // Note: ops 프로필은 명령 하나를 실행하고 끝나는 일회성 기동이다(ADR 0028). 명령이 남긴 종료 코드로 JVM을 끝낸다
        if (context.getEnvironment().acceptsProfiles(Profiles.of("ops"))) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
