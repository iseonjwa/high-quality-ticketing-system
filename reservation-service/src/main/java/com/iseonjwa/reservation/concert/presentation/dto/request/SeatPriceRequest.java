package com.iseonjwa.reservation.concert.presentation.dto.request;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SeatPriceRequest {

    // VIP, R, S 외의 값은 JSON을 읽을 때 400이 된다
    @NotNull
    private SeatGrade grade;

    // 원 단위. 상한은 schedule_seats.price NUMERIC(15, 0)에 맞춘다
    @NotNull
    @Positive
    @Max(999_999_999_999_999L)
    private Long price;
}
