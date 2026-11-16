package com.iseonjwa.reservation.global.apidocs;

// API 문서에 보이는 입장 토큰 칸(ADR 0063). 서비스는 입장 토큰을 읽지 않지만 Gateway가 요구하므로(EntryTokenPolicy)
// Swagger에서 넣을 칸을 보여 준다. 좌석 조회와 선점 API가 같은 값을 쓴다
public final class EntryTokenDocs {
    public static final String HEADER = "X-Entry-Token";
    public static final String DESCRIPTION =
            "대기열 입장 토큰. POST /api/v1/schedules/{scheduleId}/queue/token에서 받고 Gateway가 검증한다";

    private EntryTokenDocs() {}
}
