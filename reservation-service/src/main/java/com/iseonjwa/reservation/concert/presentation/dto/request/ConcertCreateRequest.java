package com.iseonjwa.reservation.concert.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ConcertCreateRequest {

    // 길이 상한은 concerts.title VARCHAR(200)과 맞춘다. 넘기면 DB 오류(500) 대신 400으로 막는다
    @NotBlank
    @Size(max = 200)
    private String title;

    private String description;
}
