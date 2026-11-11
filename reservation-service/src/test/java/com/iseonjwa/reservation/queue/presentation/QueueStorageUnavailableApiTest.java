package com.iseonjwa.reservation.queue.presentation;

import com.iseonjwa.reservation.queue.application.QueueProperties;
import com.iseonjwa.reservation.queue.domain.QueueRepository;
import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import com.iseonjwa.reservation.queue.domain.ScheduleReader;
import com.iseonjwa.reservation.queue.domain.ScheduleTimes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

// 대기열 저장소가 잠시 안 될 때 진입·순번 조회·토큰 API가 503 + Retry-After로 답하는지 확인 (ISE-59)
// Note: 저장소를 가짜로 바꾸므로 진짜 Redis를 쓰는 QueueApiTest와 클래스를 나눈다(가짜 빈은 클래스 전체에 적용된다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class QueueStorageUnavailableApiTest {

    static final long SCHEDULE_ID = 1L;
    static final long USER_ID = 42L;
    static final QueueStorageUnavailableException UNAVAILABLE =
            new QueueStorageUnavailableException(new RuntimeException("Redis command timed out"));

    @LocalServerPort
    int port;
    @Autowired
    QueueProperties queueProperties;
    @MockitoBean
    QueueRepository queueRepository;
    @MockitoBean
    ScheduleReader scheduleReader;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();


    @BeforeEach
    void 판매_중인_회차() {
        var now = Instant.now();
        when(scheduleReader.findById(SCHEDULE_ID)).thenReturn(Optional.of(
                new ScheduleTimes(SCHEDULE_ID, now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1)))));
    }

    @Test
    void 진입은_503과_Retry_After() throws Exception {
        when(queueRepository.enter(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenThrow(UNAVAILABLE);

        assertUnavailable(send("POST", queuePath()));
    }

    @Test
    void 순번_조회는_503과_Retry_After() throws Exception {
        when(queueRepository.position(anyLong(), anyLong(), anyLong())).thenThrow(UNAVAILABLE);

        assertUnavailable(send("GET", queuePath() + "/me"));
    }

    @Test
    void 토큰_발급은_503과_Retry_After() throws Exception {
        when(queueRepository.issueToken(anyLong(), anyLong(), anyLong(), anyLong())).thenThrow(UNAVAILABLE);

        assertUnavailable(send("POST", queuePath() + "/token"));
    }

    @Test
    void 다른_예외는_지금처럼_500이고_Retry_After가_없다() throws Exception {
        when(queueRepository.position(anyLong(), anyLong(), anyLong())).thenThrow(new IllegalStateException("bug"));

        var response = send("GET", queuePath() + "/me");

        assertEquals(500, response.statusCode(), response.body());
        assertEquals("INTERNAL_SERVER_ERROR", mapper.readTree(response.body()).get("code").asString());
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());
    }


    private void assertUnavailable(HttpResponse<String> response) {
        assertEquals(503, response.statusCode(), response.body());
        assertEquals(String.valueOf(queueProperties.pollMin().toSeconds()),
                response.headers().firstValue("Retry-After").orElse(null));
        assertEquals("QUEUE_TEMPORARILY_UNAVAILABLE", mapper.readTree(response.body()).get("code").asString());
    }

    private static String queuePath() {
        return "/api/v1/schedules/" + SCHEDULE_ID + "/queue";
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-User-Id", String.valueOf(USER_ID))
                .header("X-User-Role", "USER")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
