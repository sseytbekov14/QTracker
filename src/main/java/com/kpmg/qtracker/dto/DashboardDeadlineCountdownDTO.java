package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDeadlineCountdownDTO {
    // Most overdue first, capped by the requested limit
    private List<DashboardDeadlineCountdownItemDTO> overdue;
    // All overdue controls in the user's scope, including those beyond the limit
    private long overdueTotal;
    private List<DashboardDeadlineCountdownItemDTO> upcoming;
}
