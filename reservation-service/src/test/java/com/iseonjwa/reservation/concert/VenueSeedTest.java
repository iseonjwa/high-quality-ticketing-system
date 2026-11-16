package com.iseonjwa.reservation.concert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 측정 기록표의 데이터 규모가 이 시드라서 좌석 수와 등급 분포가 바뀌면 바로 알 수 있게 고정한다.
// 시드(V20261030_2)는 15,000석을 넣고, V20261116_7이 구역마다 1~2열만 남겨 1,500석으로 줄인다(ADR 0060).
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class VenueSeedTest {
    // 다른 테스트도 같은 이름의 공연장을 만들 수 있어, 시드가 먼저 넣은 가장 작은 id의 공연장을 본다
    private static final String SEAT_OF_SEED_VENUE = "FROM venue_seats s"
            + " WHERE s.venue_id = (SELECT min(id) FROM venues WHERE name = 'KSPO DOME') AND s.deleted_at IS NULL";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void KSPO_DOME_시드는_25구역_1500석이다() {
        assertEquals(1_500, jdbc.queryForObject("SELECT count(*) " + SEAT_OF_SEED_VENUE, Integer.class));
        assertEquals(25, jdbc.queryForObject("SELECT count(DISTINCT s.section) " + SEAT_OF_SEED_VENUE, Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT max(s.row_no) " + SEAT_OF_SEED_VENUE, Integer.class));
    }

    @Test
    void 등급은_VIP_300석_R_600석_S_600석이다() {
        Map<String, Integer> countByGrade = new HashMap<>();
        jdbc.query("SELECT s.grade, count(*) AS seats " + SEAT_OF_SEED_VENUE + " GROUP BY s.grade",
                rs -> {
                    countByGrade.put(rs.getString("grade"), rs.getInt("seats"));
                });

        assertEquals(Map.of("VIP", 300, "R", 600, "S", 600), countByGrade);
    }
}
