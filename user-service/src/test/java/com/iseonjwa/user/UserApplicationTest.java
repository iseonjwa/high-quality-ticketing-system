package com.iseonjwa.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL(compose와 같은 버전)에 붙어 기동하고 Flyway 마이그레이션이 적용되는지 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(UserApplicationTest.Postgres.class)
class UserApplicationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {

        // @ServiceConnection: 컨테이너 주소·계정을 datasource 설정으로 바로 넘긴다
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6-alpine");
        }
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    @Test
    void PostgreSQL에_연결되고_Flyway_마이그레이션이_적용된다() {
        assertThat(jdbcTemplate.queryForObject("select current_setting('server_version_num')::int", Integer.class))
                .isGreaterThanOrEqualTo(180000);
        assertThat(flyway.info().applied()).isNotEmpty();
    }
}
