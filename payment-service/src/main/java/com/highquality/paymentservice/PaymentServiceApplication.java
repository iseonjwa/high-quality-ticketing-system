package com.highquality.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// common의 예외 처리기를 가져오려고 common 패키지를 함께 스캔한다.
@SpringBootApplication(scanBasePackages = {"com.highquality.paymentservice", "com.highquality.common"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

}
