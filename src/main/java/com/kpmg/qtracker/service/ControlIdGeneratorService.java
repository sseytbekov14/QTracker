package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.repository.ControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    /** The months of the fiscal year in order, October first, as the last segment of a Monthly ID names them. */
    static final List<String> FISCAL_MONTHS =
            List.of("OCT", "NOV", "DEC", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP");
    /** A fiscal quarter as the last segment of a Quarterly ID: 1Q or Q1 (both are in use), any case. */
    private static final Pattern QUARTER_SEGMENT = Pattern.compile("([1-4])Q|Q([1-4])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FY_SEGMENT = Pattern.compile("(FY)(\\d{2})", Pattern.CASE_INSENSITIVE);

    /**
     * The Control ID proposed for the next cycle of a Monthly or Quarterly control (Create Control, business decision
     * of 2026-10-14): only the last segment, the period, moves on - the month (OCT -> NOV) or the fiscal quarter
     * (1Q -> 2Q, Q1 -> Q2: the form it is written in) - and the rest stays as it is. Into a new fiscal year
     * (SEP -> OCT, 4Q -> 1Q, Q4 -> Q1) the FYnn segment goes up by one as well (TODO: BUSINESS CONFIRMATION). Empty when the ID is not in that form, or another frequency:
     * the person then types the ID.
     * <p>
     * HR-CTRL-MF-3/FY27/Central/OCT -> HR-CTRL-MF-3/FY27/Central/NOV; .../FY27/Central/4Q -> .../FY28/Central/1Q
     */
    public static Optional<String> nextPeriodId(String controlId, ControlFrequency frequency) {
        if (controlId == null || controlId.isBlank()
                || (frequency != ControlFrequency.MONTHLY && frequency != ControlFrequency.QUARTERLY)) {
            return Optional.empty();
        }
        String[] segments = controlId.trim().split("/", -1);
        if (segments.length < 2) {
            return Optional.empty();
        }
        String period = segments[segments.length - 1];
        String next;
        boolean newFiscalYear;
        if (frequency == ControlFrequency.MONTHLY) {
            int month = FISCAL_MONTHS.indexOf(period.toUpperCase(Locale.ROOT));
            if (month < 0) {
                return Optional.empty();
            }
            next = FISCAL_MONTHS.get((month + 1) % FISCAL_MONTHS.size());
            newFiscalYear = month == FISCAL_MONTHS.size() - 1;
        } else {
            Matcher quarter = QUARTER_SEGMENT.matcher(period);
            if (!quarter.matches()) {
                return Optional.empty();
            }
            boolean letterFirst = quarter.group(1) == null;
            int number = Integer.parseInt(letterFirst ? quarter.group(2) : quarter.group(1));
            next = letterFirst ? "Q" + (number % 4 + 1) : (number % 4 + 1) + "Q";
            newFiscalYear = number == 4;
        }
        // The period keeps the case it was written in (oct -> nov)
        segments[segments.length - 1] = period.equals(period.toLowerCase(Locale.ROOT))
                ? next.toLowerCase(Locale.ROOT) : next;
        if (newFiscalYear) {
            int fySegment = -1;
            for (int i = 0; i < segments.length - 1; i++) {
                if (FY_SEGMENT.matcher(segments[i]).matches()) {
                    if (fySegment >= 0) {
                        return Optional.empty(); // two FY segments: which one moves is not known
                    }
                    fySegment = i;
                }
            }
            if (fySegment < 0) {
                return Optional.empty();
            }
            Matcher fy = FY_SEGMENT.matcher(segments[fySegment]);
            fy.matches();
            segments[fySegment] = fy.group(1) + String.format(Locale.ROOT, "%02d", (Integer.parseInt(fy.group(2)) + 1) % 100);
        }
        return Optional.of(String.join("/", segments));
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
