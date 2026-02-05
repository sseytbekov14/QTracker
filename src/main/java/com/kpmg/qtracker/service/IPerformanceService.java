package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlPerformance;
import java.util.Optional;

public interface IPerformanceService {
    Optional<ControlPerformance> findByControlId(Long controlId);
    PerformanceDTO convertToDTO(ControlPerformance performance, Control control);
    ControlPerformance savePerformance(PerformanceDTO performanceDTO, Control control);
    String getPerformanceStatusByControlId(Long controlId);
}