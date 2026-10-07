package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Action Centre's list of one component's controls (/component/{code}), or of the KDN controls
 * (/component/KDN): rows, counters, sorting, search, status filter and pages, all over the controls the user
 * already sees (the policy decided that before; nothing here widens or narrows it). The counters are the same
 * ones the component's card shows, so the card, the list and each counter's link agree.
 */
public final class ComponentControlsList {

    /** The KDN controls' list code; not a component, see {@link AccessPolicy#isKdnControl}. */
    public static final String KDN_CODE = "KDN";
    public static final String KDN_NAME = "KDN controls";
    /** Every component's controls together (the "All components" card). */
    public static final String ALL_CODE = "ALL";
    public static final String ALL_NAME = "All components";

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final List<Integer> PAGE_SIZES = List.of(25, 50);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Workflow order, for sorting by status. */
    private static final List<String> STATUS_ORDER =
            List.of("DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");

    /** The review steps, counted together as "In review". */
    public static final Set<String> REVIEW_STATUSES = Set.of("REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW");

    private ComponentControlsList() {
    }

    // ---------------------------------------------------------------- which controls

    /** Whether the control belongs to the component (code compared trimmed, any case), as on the card. */
    public static boolean inComponent(String code, ControlResponseDTO control) {
        return code != null && control != null && control.getComponent() != null
                && code.trim().equalsIgnoreCase(control.getComponent().trim());
    }

    /** The controls of the list {@code code}: a component, or {@link #KDN_CODE} for the KDN controls. */
    public static List<ControlResponseDTO> controlsOf(String code, Collection<ControlResponseDTO> visible) {
        if (visible == null) {
            return List.of();
        }
        if (KDN_CODE.equalsIgnoreCase(code)) {
            return KdnControlsOverview.kdnControls(visible);
        }
        return visible.stream().filter(control -> inComponent(code, control)).toList();
    }

    // ---------------------------------------------------------------- counters

    /**
     * The card's and the list's counters. Each control counts once in its status (drafts only in the total);
     * overdue is {@link DeadlineOverdue#isOverdue}, on top of its status.
     */
    public record Counts(long total, long drafts, long inProgress, long inReview, long completed, long overdue) {

        public static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0);

        public static Counts of(Collection<ControlResponseDTO> controls, LocalDate today) {
            long total = 0, drafts = 0, inProgress = 0, inReview = 0, completed = 0, overdue = 0;
            for (ControlResponseDTO control : controls == null ? List.<ControlResponseDTO>of() : controls) {
                if (control == null) {
                    continue;
                }
                total++;
                String status = statusOf(control);
                if ("DRAFT".equals(status)) {
                    drafts++;
                } else if ("IN_PROGRESS".equals(status)) {
                    inProgress++;
                } else if (REVIEW_STATUSES.contains(status)) {
                    inReview++;
                } else if ("COMPLETED".equals(status)) {
                    completed++;
                }
                if (DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today)) {
                    overdue++;
                }
            }
            return new Counts(total, drafts, inProgress, inReview, completed, overdue);
        }

        /** Not completed and not overdue: the grey part of the card's bar. */
        public long active() {
            return total - completed - overdue;
        }

        public int completedPercent() {
            return total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        }
    }

    // ---------------------------------------------------------------- the columns

    /** A column of the list: its sort key and its header. */
    public record Column(String key, String label) {
    }

    /** The ten columns, in this order (business list, 2026-10-07). */
    public static final List<Column> COLUMNS = List.of(
            new Column("id", "Control ID"),
            new Column("type", "Control Type"),
            new Column("frequency", "Control Frequency"),
            new Column("facilitator", "Facilitator / Preparer(s)"),
            new Column("operator", "Control Operator"),
            new Column("owner", "Process Owner"),
            new Column("soqm", "SoQM Lead / Delegate"),
            new Column("category", "Control Category"),
            new Column("date", "Control Operation Date"),
            new Column("status", "Performance Status"));

    // ---------------------------------------------------------------- the query

    /** Sortable columns, by their URL key. */
    public enum Sort {
        ID("id"), TYPE("type"), FREQUENCY("frequency"), FACILITATOR("facilitator"), OPERATOR("operator"),
        OWNER("owner"), SOQM("soqm"), CATEGORY("category"), DATE("date"), STATUS("status");

        private final String key;

        Sort(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        static Sort of(String key) {
            return Arrays.stream(values()).filter(sort -> sort.key.equalsIgnoreCase(trim(key))).findFirst().orElse(ID);
        }
    }

    /** The status filter: one status, the review steps together, or overdue. */
    public enum StatusFilter {
        ALL("", "All statuses"),
        DRAFT("DRAFT", "Draft"),
        IN_PROGRESS("IN_PROGRESS", "In Progress"),
        IN_REVIEW("IN_REVIEW", "In review (all review steps)"),
        REVIEW("REVIEW", "Review"),
        SOQM_HEAD_REVIEW("SOQM_HEAD_REVIEW", "SoQM Head Review"),
        PROCESS_OWNER_REVIEW("PROCESS_OWNER_REVIEW", "Process Owner Review"),
        COMPLETED("COMPLETED", "Completed"),
        OVERDUE("OVERDUE", "Overdue");

        private final String key;
        private final String label;

        StatusFilter(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        static StatusFilter of(String key) {
            String wanted = trim(key);
            return Arrays.stream(values()).filter(filter -> filter.key.equalsIgnoreCase(wanted)).findFirst().orElse(ALL);
        }

        boolean matches(ControlResponseDTO control, LocalDate today) {
            String status = statusOf(control);
            return switch (this) {
                case ALL -> true;
                case IN_REVIEW -> REVIEW_STATUSES.contains(status);
                case OVERDUE -> DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today);
                default -> key.equals(status);
            };
        }
    }

    /** What the user asked for in the URL, made safe: unknown values fall back to the defaults. */
    public record Query(String q, StatusFilter status, Sort sort, boolean desc, int page, int size) {

        public static Query of(String q, String status, String sort, String dir, Integer page, Integer size) {
            return new Query(trim(q), StatusFilter.of(status), Sort.of(sort), "desc".equalsIgnoreCase(trim(dir)),
                    page == null || page < 1 ? 1 : page,
                    size != null && PAGE_SIZES.contains(size) ? size : DEFAULT_PAGE_SIZE);
        }

        public boolean filtered() {
            return !q.isEmpty() || status != StatusFilter.ALL;
        }
    }

    // ---------------------------------------------------------------- rows

    /** One line of the list; people already as names, joined by ", ". */
    public record Row(Long id, String controlId, String type, String frequency, String facilitators,
                      String operators, String owners, String soqmLeads, String category,
                      LocalDate operationDate, String status, boolean overdue) {

        /** Every control, a draft too, opens on View Control. */
        public String href() {
            return "/view-control/" + id;
        }

        public String operationDateText() {
            return operationDate == null ? "" : DATE.format(operationDate);
        }
    }

    /** One choice of the phone's "Sort by". */
    public record SortOption(String label, String href, boolean selected) {
    }

    /** The page of rows and how to get to the others. */
    public record Result(String basePath, Query query, Counts counts, List<Row> rows, int matching, int page,
                         int pages) {

        public int from() {
            return matching == 0 ? 0 : (page - 1) * query.size() + 1;
        }

        public int to() {
            return Math.min(page * query.size(), matching);
        }

        /** The column's header link: ascending first, the other way when it is already sorted ascending. */
        public String sortHref(String key) {
            Sort sort = Sort.of(key);
            boolean desc = query.sort() == sort && !query.desc();
            return href(query.q(), query.status(), sort, desc, 1, query.size());
        }

        /** Every column both ways, for the phone's "Sort by" (the headers are hidden there). */
        public List<SortOption> sortOptions() {
            List<SortOption> options = new ArrayList<>();
            for (Column column : COLUMNS) {
                Sort sort = Sort.of(column.key());
                for (boolean desc : new boolean[]{false, true}) {
                    options.add(new SortOption(column.label() + (desc ? ", descending" : ", ascending"),
                            href(query.q(), query.status(), sort, desc, 1, query.size()),
                            query.sort() == sort && query.desc() == desc));
                }
            }
            return options;
        }

        /** aria-sort of the column's header. */
        public String ariaSort(String key) {
            if (query.sort() != Sort.of(key)) {
                return "none";
            }
            return query.desc() ? "descending" : "ascending";
        }

        public String pageHref(int page) {
            return href(query.q(), query.status(), query.sort(), query.desc(), page, query.size());
        }

        public String sizeHref(int size) {
            return href(query.q(), query.status(), query.sort(), query.desc(), 1, size);
        }

        /** A counter's link: that status, no search, the same sorting. */
        public String statusHref(String status) {
            return href("", StatusFilter.of(status), query.sort(), query.desc(), 1, query.size());
        }

        public String clearHref() {
            return href("", StatusFilter.ALL, query.sort(), query.desc(), 1, query.size());
        }

        public List<Column> columns() {
            return COLUMNS;
        }

        public List<StatusFilter> statusFilters() {
            return List.of(StatusFilter.values());
        }

        public List<Integer> pageSizes() {
            return PAGE_SIZES;
        }

        /** Page numbers to show: the first, the last and two around the current one; 0 is a gap. */
        public List<Integer> pageLinks() {
            List<Integer> links = new ArrayList<>();
            for (int number = 1; number <= pages; number++) {
                if (number == 1 || number == pages || Math.abs(number - page) <= 2) {
                    links.add(number);
                } else if (!links.isEmpty() && links.get(links.size() - 1) != 0) {
                    links.add(0);
                }
            }
            return links;
        }

        private String href(String q, StatusFilter status, Sort sort, boolean desc, int page, int size) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromPath(basePath);
            if (!q.isEmpty()) {
                builder.queryParam("q", q);
            }
            if (status != StatusFilter.ALL) {
                builder.queryParam("status", status.key());
            }
            if (sort != Sort.ID || desc) {
                builder.queryParam("sort", sort.key());
                builder.queryParam("dir", desc ? "desc" : "asc");
            }
            if (page > 1) {
                builder.queryParam("page", page);
            }
            if (size != DEFAULT_PAGE_SIZE) {
                builder.queryParam("size", size);
            }
            return builder.build().encode().toUriString();
        }
    }

    // ---------------------------------------------------------------- building the list

    /** Every e-mail in the people slots, lower-case, for one look-up of the names. */
    public static Set<String> emailsOf(Collection<ControlResponseDTO> controls) {
        Set<String> emails = new LinkedHashSet<>();
        for (ControlResponseDTO control : controls == null ? List.<ControlResponseDTO>of() : controls) {
            for (List<String> slot : slotsOf(control)) {
                for (String person : slot == null ? List.<String>of() : slot) {
                    if (person != null && person.contains("@")) {
                        emails.add(person.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return emails;
    }

    /**
     * A person's name: from the users table ({@code names}, keyed by lower-case e-mail), else the e-mail's local
     * part; a value that is not an e-mail is shown as it is.
     */
    public static String personName(String person, Map<String, String> names) {
        if (person == null || person.isBlank()) {
            return "";
        }
        String trimmed = person.trim();
        int at = trimmed.indexOf('@');
        if (at < 0) {
            return trimmed;
        }
        String name = names == null ? null : names.get(trimmed.toLowerCase(Locale.ROOT));
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return at == 0 ? trimmed : trimmed.substring(0, at);
    }

    static String joinNames(List<String> people, Map<String, String> names) {
        if (people == null) {
            return "";
        }
        return people.stream()
                .map(person -> personName(person, names))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.joining(", "));
    }

    public static Row rowOf(ControlResponseDTO control, Map<String, String> names, LocalDate today) {
        return new Row(control.getId(), control.getControlId(), control.getControlType(),
                control.getControlFrequency(),
                joinNames(control.getFacilitators(), names), joinNames(control.getControlOperators(), names),
                joinNames(control.getProcessOwners(), names), joinNames(control.getSoqmLeads(), names),
                control.getControlCategory(), control.getControlOperationDate(), statusOf(control),
                DeadlineOverdue.isOverdue(control.getPerformanceStatus(), control.getDeadline(), today));
    }

    /**
     * The list's page: counters over all {@code controls}; the status filter and the search (Control ID or a
     * person's name or e-mail, every word must match) narrow the rows; then sorting and the page.
     */
    public static Result of(String basePath, Collection<ControlResponseDTO> controls, Map<String, String> names,
                            Query query, LocalDate today) {
        List<ControlResponseDTO> all = controls == null ? List.of()
                : controls.stream().filter(Objects::nonNull).toList();
        Counts counts = Counts.of(all, today);
        List<String> terms = Arrays.stream(query.q().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(term -> !term.isEmpty())
                .toList();
        List<Row> rows = all.stream()
                .filter(control -> query.status().matches(control, today))
                .filter(control -> terms.isEmpty() || matches(control, names, terms))
                .map(control -> rowOf(control, names, today))
                .sorted(comparator(query.sort(), query.desc()))
                .toList();
        int pages = Math.max(1, (rows.size() + query.size() - 1) / query.size());
        int page = Math.min(query.page(), pages);
        List<Row> pageRows = rows.subList((page - 1) * query.size(), Math.min(page * query.size(), rows.size()));
        return new Result(basePath, query, counts, List.copyOf(pageRows), rows.size(), page, pages);
    }

    private static boolean matches(ControlResponseDTO control, Map<String, String> names, List<String> terms) {
        StringBuilder text = new StringBuilder(Objects.toString(control.getControlId(), ""));
        for (List<String> slot : slotsOf(control)) {
            for (String person : slot == null ? List.<String>of() : slot) {
                if (person != null) {
                    text.append(' ').append(person).append(' ').append(personName(person, names));
                }
            }
        }
        String haystack = text.toString().toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(haystack::contains);
    }

    /** Empty values last in either direction; ties by Control ID, then by the database id. */
    static Comparator<Row> comparator(Sort sort, boolean desc) {
        Comparator<Row> byColumn = switch (sort) {
            case ID -> text(Row::controlId, desc);
            case TYPE -> text(Row::type, desc);
            case FREQUENCY -> text(Row::frequency, desc);
            case FACILITATOR -> text(Row::facilitators, desc);
            case OPERATOR -> text(Row::operators, desc);
            case OWNER -> text(Row::owners, desc);
            case SOQM -> text(Row::soqmLeads, desc);
            case CATEGORY -> text(Row::category, desc);
            case DATE -> nullsLast(Row::operationDate, Comparator.<LocalDate>naturalOrder(), desc);
            case STATUS -> nullsLast(row -> STATUS_ORDER.contains(row.status()) ? STATUS_ORDER.indexOf(row.status()) : null,
                    Comparator.<Integer>naturalOrder(), desc);
        };
        return byColumn
                .thenComparing(text(Row::controlId, false))
                .thenComparing(Row::id, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<Row> text(Function<Row, String> value, boolean desc) {
        return nullsLast(row -> {
            String text = value.apply(row);
            return text == null || text.isBlank() ? null : text.trim().toLowerCase(Locale.ROOT);
        }, Comparator.<String>naturalOrder(), desc);
    }

    private static <T> Comparator<Row> nullsLast(Function<Row, T> value, Comparator<T> order, boolean desc) {
        return Comparator.comparing(value, Comparator.nullsLast(desc ? order.reversed() : order));
    }

    private static List<List<String>> slotsOf(ControlResponseDTO control) {
        return Arrays.asList(control.getFacilitators(), control.getControlOperators(),
                control.getProcessOwners(), control.getSoqmLeads());
    }

    /** Upper-case status; none counts as a draft (as the DTO and View Control do). */
    static String statusOf(ControlResponseDTO control) {
        String status = control.getPerformanceStatus();
        return status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
