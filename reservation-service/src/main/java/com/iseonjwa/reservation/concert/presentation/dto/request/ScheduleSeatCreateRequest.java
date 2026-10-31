package com.iseonjwa.reservation.concert.presentation.dto.request;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.global.vo.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
public class ScheduleSeatCreateRequest {

    @NotEmpty
    @Valid
    private List<SeatPriceRequest> prices;

    // 등급별 가격 표로 바꾼다. 같은 등급이 두 번 오면 어느 가격인지 알 수 없어 400이다
    public Map<SeatGrade, Money> toPriceMap() {
        Map<SeatGrade, Money> priceMap = new EnumMap<>(SeatGrade.class);
        for (SeatPriceRequest seatPrice : prices) {
            if (priceMap.put(seatPrice.getGrade(), Money.of(seatPrice.getPrice())) != null) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
        return priceMap;
    }
}
