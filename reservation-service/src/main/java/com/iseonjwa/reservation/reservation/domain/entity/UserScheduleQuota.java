package com.iseonjwa.reservation.reservation.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자·회차별 보유 매수(D1). HELD·CONFIRMED 좌석 수를 센다.
 *
 * <p>Note: 같은 사용자의 동시 선점 요청을 이 행 하나의 행 락에서 줄 세운다. 행 보장은
 * {@code INSERT ... ON CONFLICT DO NOTHING}, 증가는 {@code held_count + n <= 한도} 조건부 UPDATE로 하고(SA 12-7),
 * 엔티티로 읽어 더한 뒤 저장하지 않는다. 읽고 쓰는 사이에 다른 요청이 끼어들어 한도를 넘길 수 있기 때문이다.
 *
 * <p>TODO: MVP 기준선은 보유 매수를 읽어 검사한 뒤 upsert로 더한다(ADR 0035). 동시 요청이면 한도를 넘길 수 있고,
 * M4 동시성 트러블슈팅(TS-2)에서 재현한 뒤 위의 조건부 UPDATE로 바꾼다.
 */
@Getter
@Entity
@Table(name = "user_schedule_quotas")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserScheduleQuota extends BaseEntity {
    @EmbeddedId
    private UserScheduleQuotaId id;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "held_count", nullable = false, insertable = false, updatable = false)
    private int heldCount;
}
