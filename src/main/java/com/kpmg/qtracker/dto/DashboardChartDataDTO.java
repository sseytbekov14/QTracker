package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardChartDataDTO {
    private List<String> labels;
    private List<Long> values;
}
