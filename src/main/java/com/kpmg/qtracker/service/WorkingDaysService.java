package com.kpmg.qtracker.service;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
public class WorkingDaysService {

    public boolean isWorkingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    public LocalDate addWorkingDays(LocalDate start, int workingDays) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        if (workingDays == 0) {
            return start;
        }
        int step = workingDays > 0 ? 1 : -1;
        int remaining = Math.abs(workingDays);
        LocalDate current = start;
        while (remaining > 0) {
            current = current.plusDays(step);
            if (isWorkingDay(current)) {
                remaining--;
            }
        }
        return current;
    }

    public int workingDaysBetween(LocalDate startExclusive, LocalDate endInclusive) {
        if (startExclusive == null || endInclusive == null || !endInclusive.isAfter(startExclusive)) {
            return 0;
        }
        int count = 0;
        LocalDate current = startExclusive;
        while (current.isBefore(endInclusive)) {
            current = current.plusDays(1);
            if (isWorkingDay(current)) {
                count++;
            }
        }
        return count;
    }
}
