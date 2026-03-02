package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardCalendarEventDTO {
    private String title;
    private String start;
    private String url;
    private String color;
}
