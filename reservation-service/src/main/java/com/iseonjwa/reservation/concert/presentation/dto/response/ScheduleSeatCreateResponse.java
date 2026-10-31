package com.iseonjwa.reservation.concert.presentation.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ScheduleSeatCreateResponse {

    private Long scheduleId;

    private int createdSeats;
}
