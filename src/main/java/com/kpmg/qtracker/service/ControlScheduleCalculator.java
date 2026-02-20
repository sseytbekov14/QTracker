package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class ControlScheduleCalculator {

    public LocalDate calculateDeadline(ControlFrequency frequency, LocalDate operationDate) {
        validateInputs(frequency, operationDate);
        return switch (frequency) {
            case MONTHLY -> operationDate.plusDays(7);
            case QUARTERLY, RECURRING, AD_HOC -> operationDate.plusDays(14);
            case SEMI_ANNUAL, ANNUAL -> operationDate.plusMonths(1);
        };
    }

    public LocalDate calculateNextDate(ControlFrequency frequency, LocalDate operationDate) {
        validateInputs(frequency, operationDate);
        return switch (frequency) {
            case MONTHLY -> operationDate.plusMonths(1);
            case QUARTERLY -> operationDate.plusMonths(3);
            case SEMI_ANNUAL -> operationDate.plusMonths(6);
            case ANNUAL -> operationDate.plusMonths(12);
            case RECURRING -> operationDate.plusMonths(3);
            case AD_HOC -> null;
        };
    }

    private void validateInputs(ControlFrequency frequency, LocalDate operationDate) {
        if (operationDate == null) {
            throw new IllegalArgumentException("operationDate must not be null");
        }
        if (frequency == null) {
            throw new IllegalArgumentException("frequency must not be null");
        }
    }
}
