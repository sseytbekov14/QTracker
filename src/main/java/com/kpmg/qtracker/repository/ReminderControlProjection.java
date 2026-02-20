package com.kpmg.qtracker.repository;

import java.time.LocalDate;

public interface ReminderControlProjection {
    Long getControlId();
    String getControlName();
    String getControlDescription();
    String getFrequency();
    String getStatus();
    LocalDate getOperationDate();
    LocalDate getDeadlineDate();
    String getFacilitator();
    String getControlOperator();
    String getSoqmLead();
    String getProcessOwner();
}
