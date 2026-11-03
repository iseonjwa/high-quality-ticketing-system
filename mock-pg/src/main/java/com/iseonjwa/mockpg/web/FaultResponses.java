package com.iseonjwa.mockpg.web;

import com.iseonjwa.mockpg.fault.Fault;
import com.iseonjwa.mockpg.fault.FaultMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

/**
 * 원장을 건드리지 않고 장애가 직접 만드는 응답(mock-pg.md 11절). 승인·망취소·조회와 환불·환불 조회가 함께 쓴다.
 */
public final class FaultResponses {
    private static final StatusBody PROCESSING = new StatusBody("PROCESSING");

    private FaultResponses() {
    }

    /** 해당하는 장애가 아니면 비어 있다. */
    public static Optional<ResponseEntity<?>> of(Fault fault) {
        if (fault.is(FaultMode.PROCESSING)) return Optional.of(processing());
        if (fault.is(FaultMode.SERVER_ERROR)) {
            return Optional.of(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.SERVER_ERROR));
        }
        return Optional.empty();
    }

    public static ResponseEntity<?> processing() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(PROCESSING);
    }

    private record StatusBody(String status) {
    }
}
