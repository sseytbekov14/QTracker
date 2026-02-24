package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import java.util.Optional;

public interface IPerformanceService {
    PerformanceDTO buildPerformanceDTO(Control control);
    void saveSoqmYear(Long controlId, String soqmYear);
    String getPerformanceStatusByControlId(Long controlId);
}