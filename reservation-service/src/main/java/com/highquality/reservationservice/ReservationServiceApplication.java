package com.highquality.reservationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// common의 예외 처리기를 가져오려고 common 패키지를 함께 스캔한다.
@SpringBootApplication(scanBasePackages = {"com.highquality.reservationservice", "com.highquality.common"})
public class ReservationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationServiceApplication.class, args);
    }

}
