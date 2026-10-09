package com.kpmg.qtracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDeadlineCountdownItemDTO {
    private Long id;
    private String controlId;
    private String name;
    // End of the deadline day in Almaty, with offset: "2026-10-01T23:59:00+05:00"
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime deadline;
    private String status;
    private String url;
    private boolean overdue;
    private long daysOverdue;
}
