package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 결제 서비스의 결제·예매 대사(ADR 0029)가 읽는 예매 조회. 읽기만 하고 아무것도 고치지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationQueryService {
    public static final int MAX_IDS = 100;
    public static final int PAGE_SIZE = 100;

    private final ReservationRepository reservations;

    // ID 목록 조회. 결제 쪽에서 출발해 예매 상태를 맞춰 볼 때 쓴다(RC-1~RC-3, RC-5, RC-6)
    public List<Reservation> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return reservations.findAllByIds(ids);
    }

    // 기간 안에 CONFIRMED·CANCELED가 된 예매. 결제 쪽에서 출발하면 찾지 못하는 "승인 없는 확정"(RC-4)용이다
    public List<Reservation> findSettledChanges(Instant from, Instant to, Long cursor) {
        if (!from.isBefore(to) || (cursor != null && cursor < 0)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return reservations.findSettledChanges(from, to, cursor == null ? 0 : cursor, PAGE_SIZE);
    }
}
