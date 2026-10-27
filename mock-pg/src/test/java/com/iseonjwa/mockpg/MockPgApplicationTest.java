package com.iseonjwa.mockpg;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class MockPgApplicationTest {

    @Test
    void Config_서버_없이_test_프로필로_뜬다() {
    }
}
