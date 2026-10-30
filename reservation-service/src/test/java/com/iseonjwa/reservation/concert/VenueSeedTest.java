package com.iseonjwa.reservation.concert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 측정 기록표의 데이터 규모가 이 시드라서 좌석 수와 등급 분포가 바뀌면 바로 알 수 있게 고정한다
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class VenueSeedTest {
    private static final String SEAT_OF_SEED_VENUE =
            "FROM venue_seats s JOIN venues v ON v.id = s.venue_id WHERE v.name = 'KSPO DOME'";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void KSPO_DOME_시드는_25구역_15000석이다() {
        assertEquals(15_000, jdbc.queryForObject("SELECT count(*) " + SEAT_OF_SEED_VENUE, Integer.class));
        assertEquals(25, jdbc.queryForObject("SELECT count(DISTINCT s.section) " + SEAT_OF_SEED_VENUE, Integer.class));
    }

    @Test
    void 등급은_VIP_3000석_R_6000석_S_6000석이다() {
        Map<String, Integer> countByGrade = new HashMap<>();
        jdbc.query("SELECT s.grade, count(*) AS seats " + SEAT_OF_SEED_VENUE + " GROUP BY s.grade",
                rs -> {
                    countByGrade.put(rs.getString("grade"), rs.getInt("seats"));
                });

        assertEquals(Map.of("VIP", 3_000, "R", 6_000, "S", 6_000), countByGrade);
    }
}
