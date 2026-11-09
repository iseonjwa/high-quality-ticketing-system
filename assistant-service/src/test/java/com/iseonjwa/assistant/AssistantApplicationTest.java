package com.iseonjwa.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantApplicationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void 설정_서버_없이_test_프로필로_기동하고_헬스가_UP이다() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
