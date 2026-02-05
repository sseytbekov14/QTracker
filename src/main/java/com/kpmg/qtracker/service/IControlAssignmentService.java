package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.ControlAssignment;

public interface IControlAssignmentService {
    ControlAssignment saveAssignment(ControlAssignmentDTO assignmentDTO);
    ControlAssignmentDTO getAssignmentByControlId(Long controlId);
}