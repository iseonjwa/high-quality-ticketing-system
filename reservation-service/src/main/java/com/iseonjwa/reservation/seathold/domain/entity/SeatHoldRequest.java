package com.iseonjwa.reservation.seathold.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.util.List;
import java.util.UUID;

/**
 * 좌석 선점 접수 요청(SA 12-3, #33). 동기형·접수형이 같은 행을 남기고, 선점 API의 멱등은 이 행의 PK가 맡는다(#17).
 * 상태 전이는 {@code WHERE status = 'REQUESTED'} 조건부 UPDATE로만 한다. 시한을 넘겨 닫힌 요청에
 * 늦게 온 처리 결과가 좌석을 잡지 않게 하기 위해서다.
 */
@Getter
@Entity
@Table(name = "seat_hold_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatHoldRequest extends BaseEntity implements Persistable<UUID> {
    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private Long scheduleId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "seat_ids", nullable = false, updatable = false, columnDefinition = "bigint[]")
    private List<Long> seatIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SeatHoldRequestStatus status;

    // HELD일 때 만든 예매
    @Column(name = "reservation_id", updatable = false)
    private Long reservationId;

    // REJECTED일 때 사유. 예: SEAT_ALREADY_HELD, PURCHASE_LIMIT_EXCEEDED, TIMEOUT
    @Column(name = "reason_code", updatable = false, length = 40)
    private String reasonCode;

    // 이 선점에 쓴 입장 토큰의 jti. 입장 토큰 없이 들어온 요청은 null이다(ADR 0053)
    @Column(name = "entry_token_jti", updatable = false, length = 64)
    private String entryTokenJti;

    // 자동 배정 선점의 조건(구역·등급·매수). 같은 멱등키 재요청을 이 값으로 비교한다. 좌석을 직접 고른 요청은 null이다(ISE-264)
    @Column(name = "auto_criteria", updatable = false, length = 40)
    private String autoCriteria;

    // Note: ID를 직접 정하는 엔티티는 Spring Data가 새 엔티티로 보지 않고 merge로 저장한다. merge는 같은 PK가 있으면
    // 위반 없이 넘어가 "같은 멱등키 재요청은 PK 충돌로 감지한다"는 계약이 깨진다. 저장 전까지 새 엔티티로 표시해 persist로 보낸다.
    @Getter(AccessLevel.NONE)
    @Transient
    private boolean isNew = true;

    private SeatHoldRequest(UUID requestId, Long userId, Long scheduleId, List<Long> seatIds, String entryTokenJti) {
        this.requestId = requestId;
        this.userId = userId;
        this.scheduleId = scheduleId;
        this.seatIds = List.copyOf(seatIds);
        this.status = SeatHoldRequestStatus.REQUESTED;
        this.entryTokenJti = entryTokenJti;
    }

    public static SeatHoldRequest request(UUID requestId, Long userId, Long scheduleId, List<Long> seatIds) {
        return request(requestId, userId, scheduleId, seatIds, null);
    }

    public static SeatHoldRequest request(UUID requestId, Long userId, Long scheduleId, List<Long> seatIds,
            String entryTokenJti) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("선점할 좌석이 없습니다");
        }
        return new SeatHoldRequest(requestId, userId, scheduleId, seatIds, entryTokenJti);
    }

    // Note: 자동 배정은 좌석을 잠그기 전에 이 행을 먼저 써서 같은 멱등키 연타를 PK로 줄 세운다. 그때는 좌석을 모르므로
    // 빈 배열로 쓰고, 같은 트랜잭션에서 좌석을 고른 뒤 markAutoHeld가 채운다. 커밋된 행에는 늘 좌석이 있다(seat_ids NOT NULL 유지)
    public static SeatHoldRequest autoAssign(UUID requestId, Long userId, Long scheduleId, String autoCriteria,
            String entryTokenJti) {
        var request = new SeatHoldRequest(requestId, userId, scheduleId, List.of(), entryTokenJti);
        request.autoCriteria = autoCriteria;
        return request;
    }

    // 동기형은 선점과 같은 트랜잭션에서 결과까지 정해지므로 REQUESTED를 거치지 않고 HELD로 저장한다(ADR 0032)
    public static SeatHoldRequest held(UUID requestId, Long userId, Long scheduleId, List<Long> seatIds,
            Long reservationId) {
        var request = request(requestId, userId, scheduleId, seatIds);
        request.status = SeatHoldRequestStatus.HELD;
        request.reservationId = reservationId;
        return request;
    }

    @Override
    public UUID getId() {
        return requestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
