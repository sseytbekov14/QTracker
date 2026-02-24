package com.kpmg.qtracker.service;

import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates Control IDs in the format:
 * {Component}-CTRL-MF-{Number}/{FiscalYear}/{Location}[/{Period}]
 *
 * Examples:
 *   Annual:      EP-CTRL-MF-108/FY26/Central
 *   Semi-Annual: HR-CTRL-MF-300/FY26/Central/1H
 *   Quarterly:   HR-CTRL-MF-416/FY26/Central/1Q
 *   Monthly:     HR-CTRL-MF-424/FY26/Central/OCT
 *   Recurring:   AC-CTRL-MF-11/FY26/Central
 *   Ad-hoc:      EP-CTRL-MF-109C/FY26/Central
 *
 * Note: Component codes like A&C, I&C, M&R have & stripped → AC, IC, MR
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlIdGeneratorService {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    private static final String LOCATION = "Central";

    // Pattern to extract the number from existing control IDs: {COMP}-CTRL-MF-{NUMBER}
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("^[A-Z]+\\-CTRL\\-MF\\-(\\d+)");

    private final ControlRepository controlRepository;

    /**
     * Generate a new Control ID based on component and frequency.
     *
     * @param component e.g. "HR", "EP", "A&C" (→ AC), "I&C" (→ IC), "M&R" (→ MR)
     * @param frequency e.g. "Monthly", "Quarterly", "Annual", "Semi Annual", "Recurring", "Ad-hoc"
     * @return the generated Control ID
     */
    public String generateControlId(String component, String frequency) {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("Component is required to generate Control ID");
        }
        if (frequency == null || frequency.isBlank()) {
            throw new IllegalArgumentException("Frequency is required to generate Control ID");
        }

        String comp = component.trim().toUpperCase().replace("&", "");
        String freq = normalizeFrequency(frequency.trim());

        int nextNumber = getNextNumber(comp);
        String fiscalYear = getCurrentFiscalYear();
        String period = getPeriodSuffix(freq);

        // Build: {COMP}-CTRL-MF-{NUM}/{FY}/{LOC}[/{PERIOD}]
        StringBuilder sb = new StringBuilder();
        sb.append(comp).append("-CTRL-MF-").append(nextNumber);
        sb.append("/").append(fiscalYear);
        sb.append("/").append(LOCATION);
        if (period != null && !period.isEmpty()) {
            sb.append("/").append(period);
        }

        String controlId = sb.toString();
        log.info("Generated Control ID: {}", controlId);
        return controlId;
    }

    /**
     * Generate a Control ID for auto-created controls (next period of existing control).
     *
     * @param baseControlId e.g. "HR-CTRL-MF-424"
     * @param frequency     e.g. "Monthly"
     * @param operationDate the date of the new control period
     * @return the generated Control ID
     */
    public String generateNextPeriodControlId(String baseControlId, String frequency, LocalDate operationDate) {
        if (baseControlId == null || baseControlId.isBlank()) {
            return generateControlId("AUTO", frequency);
        }

        String freq = normalizeFrequency(frequency);
        String fiscalYear = getFiscalYear(operationDate);
        String period = getPeriodSuffixForDate(freq, operationDate);

        StringBuilder sb = new StringBuilder();
        sb.append(baseControlId);
        sb.append("/").append(fiscalYear);
        sb.append("/").append(LOCATION);
        if (period != null && !period.isEmpty()) {
            sb.append("/").append(period);
        }

        String controlId = sb.toString();

        // Ensure uniqueness
        if (controlRepository.existsByControlId(controlId)) {
            for (int i = 1; i <= 100; i++) {
                String candidate = controlId + "(" + i + ")";
                if (!controlRepository.existsByControlId(candidate)) {
                    return candidate;
                }
            }
        }

        return controlId;
    }

    /**
     * Extract the base part of a Control ID (before /{FY}).
     * E.g. "HR-CTRL-MF-424/FY26/Central/OCT" → "HR-CTRL-MF-424"
     */
    public String extractBaseId(String controlId) {
        if (controlId == null || controlId.isBlank()) {
            return controlId;
        }
        int slashIndex = controlId.indexOf('/');
        if (slashIndex > 0) {
            return controlId.substring(0, slashIndex);
        }
        return controlId;
    }

    // ------- Private helpers -------

    private int getNextNumber(String component) {
        String prefix = component + "-CTRL-MF-%";
        List<String> existingIds = controlRepository.findControlIdsByPrefix(prefix);

        int maxNumber = 0;
        for (String id : existingIds) {
            Matcher matcher = NUMBER_PATTERN.matcher(id);
            if (matcher.find()) {
                try {
                    int num = Integer.parseInt(matcher.group(1));
                    if (num > maxNumber) {
                        maxNumber = num;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return maxNumber + 1;
    }

    /**
     * Fiscal year: Oct 2025 – Sep 2026 = FY26
     * If current month >= October → FY = next year's last 2 digits
     * If current month < October  → FY = this year's last 2 digits
     */
    private String getCurrentFiscalYear() {
        LocalDate today = LocalDate.now(ALMATY);
        return getFiscalYear(today);
    }

    private String getFiscalYear(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        // Fiscal year starts in October
        // Oct 2025 → FY26, Jan 2026 → FY26, Sep 2026 → FY26, Oct 2026 → FY27
        int fy = (month >= 10) ? year + 1 : year;
        return "FY" + (fy % 100);
    }

    /**
     * Get period suffix based on frequency for the CURRENT date.
     */
    private String getPeriodSuffix(String frequency) {
        LocalDate today = LocalDate.now(ALMATY);
        return getPeriodSuffixForDate(frequency, today);
    }

    /**
     * Get period suffix based on frequency for a specific date.
     */
    private String getPeriodSuffixForDate(String frequency, LocalDate date) {
        switch (frequency) {
            case "MONTHLY":
                return getMonthAbbreviation(date.getMonth());
            case "QUARTERLY":
                return getQuarterLabel(date);
            case "SEMI_ANNUAL":
                return getSemiAnnualLabel(date);
            case "ANNUAL":
            case "RECURRING":
            case "AD_HOC":
            default:
                return null; // No period suffix
        }
    }

    /**
     * Month abbreviation for Monthly controls.
     * Fiscal year runs Oct–Sep: OCT, NOV, DEC, JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP
     */
    private String getMonthAbbreviation(Month month) {
        switch (month) {
            case OCTOBER:   return "OCT";
            case NOVEMBER:  return "NOV";
            case DECEMBER:  return "DEC";
            case JANUARY:   return "JAN";
            case FEBRUARY:  return "FEB";
            case MARCH:     return "MAR";
            case APRIL:     return "APR";
            case MAY:       return "MAY";
            case JUNE:      return "JUN";
            case JULY:      return "JUL";
            case AUGUST:    return "AUG";
            case SEPTEMBER: return "SEP";
            default:        return month.name().substring(0, 3);
        }
    }

    /**
     * Fiscal quarter label.
     * Q1 = Oct–Dec, Q2 = Jan–Mar, Q3 = Apr–Jun, Q4 = Jul–Sep
     */
    private String getQuarterLabel(LocalDate date) {
        int month = date.getMonthValue();
        // Q1 = Oct(10), Nov(11), Dec(12)
        if (month >= 10) return "1Q";
        // Q2 = Jan(1), Feb(2), Mar(3)
        if (month >= 1 && month <= 3) return "2Q";
        // Q3 = Apr(4), May(5), Jun(6)
        if (month >= 4 && month <= 6) return "3Q";
        // Q4 = Jul(7), Aug(8), Sep(9)
        return "4Q";
    }

    /**
     * Semi-annual label.
     * 1H = Oct–Mar (first half of fiscal year)
     * 2H = Apr–Sep (second half of fiscal year)
     */
    private String getSemiAnnualLabel(LocalDate date) {
        int month = date.getMonthValue();
        // 1H: Oct(10), Nov(11), Dec(12), Jan(1), Feb(2), Mar(3)
        if (month >= 10 || month <= 3) return "1H";
        // 2H: Apr(4)–Sep(9)
        return "2H";
    }

    private String normalizeFrequency(String frequency) {
        if (frequency == null) return "ANNUAL";
        String upper = frequency.trim().toUpperCase().replace(" ", "_").replace("-", "_");
        switch (upper) {
            case "MONTHLY":      return "MONTHLY";
            case "QUARTERLY":    return "QUARTERLY";
            case "SEMI_ANNUAL":
            case "SEMIANNUAL":
            case "SEMI-ANNUAL":  return "SEMI_ANNUAL";
            case "ANNUAL":       return "ANNUAL";
            case "RECURRING":    return "RECURRING";
            case "AD_HOC":
            case "ADHOC":
            case "AD-HOC":       return "AD_HOC";
            default:             return "ANNUAL";
        }
    }
}
