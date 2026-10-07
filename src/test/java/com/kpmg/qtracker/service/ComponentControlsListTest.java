package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ComponentControlsListTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final Map<String, String> NAMES = Map.of(
            "anna@kpmg.kz", "Anna Petrova",
            "boris@kpmg.kz", "Boris Ivanov");

    private static ControlResponseDTO control(long id, String controlId, String component, String status,
                                              LocalDate deadline) {
        ControlResponseDTO dto = new ControlResponseDTO();
        dto.setId(id);
        dto.setControlId(controlId);
        dto.setComponent(component);
        dto.setPerformanceStatus(status);
        dto.setDeadline(deadline);
        dto.setFacilitators(List.of());
        dto.setControlOperators(List.of());
        dto.setProcessOwners(List.of());
        dto.setSoqmLeads(List.of());
        return dto;
    }

    private static ComponentControlsList.Result result(List<ControlResponseDTO> controls, String q, String status,
                                                       String sort, String dir, Integer page, Integer size) {
        return ComponentControlsList.of("/component/HR", controls, NAMES,
                ComponentControlsList.Query.of(q, status, sort, dir, page, size), TODAY);
    }

    private static List<String> ids(ComponentControlsList.Result result) {
        return result.rows().stream().map(ComponentControlsList.Row::controlId).toList();
    }

    @Test
    void tenColumns_inTheAgreedOrder() {
        assertThat(ComponentControlsList.COLUMNS).extracting(ComponentControlsList.Column::label).containsExactly(
                "Control ID", "Control Type", "Control Frequency", "Facilitator / Preparer(s)", "Control Operator",
                "Process Owner", "SoQM Lead / Delegate", "Control Category", "Control Operation Date",
                "Performance Status");
    }

    @Test
    void personName_fromTheUsersTable_elseTheLocalPart() {
        assertThat(ComponentControlsList.personName("Anna@KPMG.kz ", NAMES)).isEqualTo("Anna Petrova");
        assertThat(ComponentControlsList.personName("new.person@kpmg.kz", NAMES)).isEqualTo("new.person");
        assertThat(ComponentControlsList.personName("Legacy Name", NAMES)).isEqualTo("Legacy Name");
        assertThat(ComponentControlsList.personName("@odd", NAMES)).isEqualTo("@odd");
        assertThat(ComponentControlsList.personName(" ", NAMES)).isEmpty();
        assertThat(ComponentControlsList.personName(null, NAMES)).isEmpty();
    }

    @Test
    void row_peopleJoinedByComma_dateAsDdMmYyyy_opensViewControl_draftToo() {
        ControlResponseDTO dto = control(5, "HR-5", "HR", null, null);
        dto.setFacilitators(List.of("anna@kpmg.kz", "x.y@kpmg.kz"));
        dto.setControlOperators(List.of("BORIS@kpmg.kz"));
        dto.setControlOperationDate(LocalDate.of(2026, 3, 9));

        ComponentControlsList.Row row = ComponentControlsList.rowOf(dto, NAMES, TODAY);

        assertThat(row.facilitators()).isEqualTo("Anna Petrova, x.y");
        assertThat(row.operators()).isEqualTo("Boris Ivanov");
        assertThat(row.owners()).isEmpty();
        assertThat(row.operationDateText()).isEqualTo("09.03.2026");
        assertThat(row.status()).isEqualTo("DRAFT");
        assertThat(row.href()).isEqualTo("/view-control/5");
    }

    @Test
    void emailsOf_everySlot_lowerCase_once() {
        ControlResponseDTO a = control(1, "HR-1", "HR", "REVIEW", null);
        a.setFacilitators(List.of("Anna@kpmg.kz", "Legacy Name"));
        a.setSoqmLeads(List.of("soqm@kpmg.kz"));
        ControlResponseDTO b = control(2, "HR-2", "HR", "REVIEW", null);
        b.setProcessOwners(List.of("anna@kpmg.kz", "po@kpmg.kz"));
        b.setControlOperators(null);

        assertThat(ComponentControlsList.emailsOf(List.of(a, b)))
                .containsExactlyInAnyOrder("anna@kpmg.kz", "soqm@kpmg.kz", "po@kpmg.kz");
    }

    @Test
    void controlsOf_componentByCode_kdnByTheSharedRule() {
        List<ControlResponseDTO> visible = List.of(
                control(1, "KDN_RER-CTRL-MF-33A/FY26/Central/1Q", "RER", "IN_PROGRESS", null),
                control(2, "KDN_EP-CTRL-MF-109A/FY26/Central/DEC-SEP", "EP", "DRAFT", null),
                control(3, "HR-CTRL-MF-151A/FY26/UZB/OCT", "HR", "REVIEW", null),
                control(4, "X-KDN-12", "hr ", "REVIEW", null),
                control(5, "kdn-5", "GOV", "COMPLETED", null),
                control(6, "", "HR", "REVIEW", null));

        assertThat(ComponentControlsList.controlsOf("KDN", visible)).extracting(ControlResponseDTO::getId)
                .containsExactly(1L, 2L, 5L);
        assertThat(ComponentControlsList.controlsOf("HR", visible)).extracting(ControlResponseDTO::getId)
                .containsExactly(3L, 4L, 6L);
        assertThat(ComponentControlsList.controlsOf("HR", null)).isEmpty();
    }

    @Test
    void counts_statusOnce_overdueByDeadlineOverdue_draftsOnlyInTheTotal() {
        List<ControlResponseDTO> controls = List.of(
                control(1, "HR-1", "HR", "DRAFT", TODAY.minusDays(3)),
                control(2, "HR-2", "HR", "IN_PROGRESS", TODAY.minusDays(1)),
                control(3, "HR-3", "HR", "IN_PROGRESS", TODAY),
                control(4, "HR-4", "HR", "REVIEW", TODAY.plusDays(2)),
                control(5, "HR-5", "HR", "SOQM_HEAD_REVIEW", TODAY.minusDays(9)),
                control(6, "HR-6", "HR", "PROCESS_OWNER_REVIEW", null),
                control(7, "HR-7", "HR", "COMPLETED", TODAY.minusDays(20)),
                control(8, "HR-8", "HR", null, null));

        ComponentControlsList.Counts counts = ComponentControlsList.Counts.of(controls, TODAY);

        assertThat(counts).isEqualTo(new ComponentControlsList.Counts(8, 2, 2, 3, 1, 3));
        for (ControlResponseDTO c : controls) {
            assertThat(DeadlineOverdue.isOverdue(c.getPerformanceStatus(), c.getDeadline(), TODAY))
                    .as(c.getControlId())
                    .isEqualTo(Set.of("HR-1", "HR-2", "HR-5").contains(c.getControlId()));
        }
        assertThat(counts.active()).isEqualTo(4);
        assertThat(ComponentControlsList.Counts.of(null, TODAY)).isEqualTo(ComponentControlsList.Counts.NONE);
    }

    @Test
    void eachCounterLink_listsExactlyItsNumber() {
        List<ControlResponseDTO> controls = List.of(
                control(1, "HR-1", "HR", "DRAFT", TODAY.minusDays(3)),
                control(2, "HR-2", "HR", "IN_PROGRESS", TODAY.minusDays(1)),
                control(3, "HR-3", "HR", "REVIEW", TODAY.plusDays(2)),
                control(4, "HR-4", "HR", "SOQM_HEAD_REVIEW", TODAY.minusDays(9)),
                control(5, "HR-5", "HR", "COMPLETED", TODAY.minusDays(20)));
        ComponentControlsList.Counts counts = result(controls, null, null, null, null, null, null).counts();

        assertThat(result(controls, null, "", null, null, null, null).matching()).isEqualTo(counts.total());
        assertThat(result(controls, null, "IN_PROGRESS", null, null, null, null).matching()).isEqualTo(counts.inProgress());
        assertThat(result(controls, null, "in_review", null, null, null, null).matching()).isEqualTo(counts.inReview());
        assertThat(result(controls, null, "COMPLETED", null, null, null, null).matching()).isEqualTo(counts.completed());
        assertThat(result(controls, null, "OVERDUE", null, null, null, null).matching()).isEqualTo(counts.overdue());
        assertThat(result(controls, null, "OVERDUE", null, null, null, null).counts()).isEqualTo(counts);
    }

    @Test
    void search_byIdOrPerson_everyWord_anyCase() {
        ControlResponseDTO a = control(1, "HR-CTRL-MF-151A/FY26/UZB/OCT", "HR", "REVIEW", null);
        a.setFacilitators(List.of("anna@kpmg.kz"));
        ControlResponseDTO b = control(2, "HR-CTRL-MF-7/FY26/Central", "HR", "REVIEW", null);
        b.setProcessOwners(List.of("boris@kpmg.kz"));
        ControlResponseDTO c = control(3, "HR-9", "HR", "REVIEW", null);
        c.setSoqmLeads(List.of("zhanna.k@kpmg.kz"));
        List<ControlResponseDTO> controls = List.of(a, b, c);

        assertThat(ids(result(controls, "151a", null, null, null, null, null))).containsExactly(a.getControlId());
        assertThat(ids(result(controls, "  petrova ", null, null, null, null, null))).containsExactly(a.getControlId());
        assertThat(ids(result(controls, "BORIS fy26", null, null, null, null, null))).containsExactly(b.getControlId());
        assertThat(ids(result(controls, "zhanna.k", null, null, null, null, null))).containsExactly("HR-9");
        assertThat(ids(result(controls, "central uzb", null, null, null, null, null))).isEmpty();
        assertThat(result(controls, "nobody", null, null, null, null, null).counts().total()).isEqualTo(3);
    }

    @Test
    void sort_eachColumn_bothWays_emptyLast_tiesByControlId() {
        ControlResponseDTO a = control(1, "HR-B", "HR", "COMPLETED", null);
        a.setControlType("Manual");
        a.setControlOperationDate(LocalDate.of(2026, 1, 5));
        a.setFacilitators(List.of("boris@kpmg.kz"));
        ControlResponseDTO b = control(2, "HR-A", "HR", "DRAFT", null);
        b.setControlType("automated");
        ControlResponseDTO c = control(3, "hr-c", "HR", "REVIEW", null);
        c.setControlType("Manual");
        c.setControlOperationDate(LocalDate.of(2025, 12, 31));
        c.setFacilitators(List.of("anna@kpmg.kz"));
        List<ControlResponseDTO> controls = List.of(a, b, c);

        assertThat(ids(result(controls, null, null, null, null, null, null))).containsExactly("HR-A", "HR-B", "hr-c");
        assertThat(ids(result(controls, null, null, "id", "desc", null, null))).containsExactly("hr-c", "HR-B", "HR-A");
        assertThat(ids(result(controls, null, null, "type", "asc", null, null))).containsExactly("HR-A", "HR-B", "hr-c");
        assertThat(ids(result(controls, null, null, "type", "desc", null, null))).containsExactly("HR-B", "hr-c", "HR-A");
        assertThat(ids(result(controls, null, null, "date", null, null, null))).containsExactly("hr-c", "HR-B", "HR-A");
        assertThat(ids(result(controls, null, null, "date", "desc", null, null))).containsExactly("HR-B", "hr-c", "HR-A");
        assertThat(ids(result(controls, null, null, "facilitator", null, null, null))).containsExactly("hr-c", "HR-B", "HR-A");
        assertThat(ids(result(controls, null, null, "facilitator", "desc", null, null))).containsExactly("HR-B", "hr-c", "HR-A");
        assertThat(ids(result(controls, null, null, "status", null, null, null))).containsExactly("HR-A", "hr-c", "HR-B");
        assertThat(ids(result(controls, null, null, "status", "desc", null, null))).containsExactly("HR-B", "hr-c", "HR-A");
        assertThat(ids(result(controls, null, null, "nonsense", "sideways", null, null))).containsExactly("HR-A", "HR-B", "hr-c");
    }

    @Test
    void pages_25Or50_clampedToTheLastPage_withLinksThatKeepTheQuery() {
        List<ControlResponseDTO> controls = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            controls.add(control(i, String.format("HR-%03d", i), "HR", "REVIEW", null));
        }

        ComponentControlsList.Result first = result(controls, null, null, null, null, null, null);
        assertThat(first.rows()).hasSize(25);
        assertThat(first.pages()).isEqualTo(3);
        assertThat(first.from()).isEqualTo(1);
        assertThat(first.to()).isEqualTo(25);

        ComponentControlsList.Result third = result(controls, null, null, null, null, 3, 25);
        assertThat(ids(third)).first().isEqualTo("HR-051");
        assertThat(third.rows()).hasSize(10);
        assertThat(result(controls, null, null, null, null, 99, null).page()).isEqualTo(3);
        assertThat(result(controls, null, null, null, null, -1, null).page()).isEqualTo(1);

        ComponentControlsList.Result fifty = result(controls, null, null, null, null, 2, 50);
        assertThat(fifty.rows()).hasSize(10);
        assertThat(fifty.from()).isEqualTo(51);
        assertThat(result(controls, null, null, null, null, null, 30).query().size()).isEqualTo(25);

        ComponentControlsList.Result query = result(controls, "hr 0", "REVIEW", "date", "desc", 2, 50);
        assertThat(query.pageHref(1)).isEqualTo("/component/HR?q=hr%200&status=REVIEW&sort=date&dir=desc&size=50");
        assertThat(query.sizeHref(25)).isEqualTo("/component/HR?q=hr%200&status=REVIEW&sort=date&dir=desc");
        assertThat(query.sortHref("date")).isEqualTo("/component/HR?q=hr%200&status=REVIEW&sort=date&dir=asc&size=50");
        assertThat(query.sortHref("id")).isEqualTo("/component/HR?q=hr%200&status=REVIEW&size=50");
        assertThat(query.statusHref("OVERDUE")).isEqualTo("/component/HR?status=OVERDUE&sort=date&dir=desc&size=50");
        assertThat(query.clearHref()).isEqualTo("/component/HR?sort=date&dir=desc&size=50");
        assertThat(query.ariaSort("date")).isEqualTo("descending");
        assertThat(query.ariaSort("id")).isEqualTo("none");
    }

    @Test
    void sortHref_ascendingFirst_thenTheOtherWay() {
        List<ControlResponseDTO> controls = List.of(control(1, "HR-1", "HR", "REVIEW", null));
        assertThat(result(controls, null, null, null, null, null, null).sortHref("id")).isEqualTo("/component/HR?sort=id&dir=desc");
        assertThat(result(controls, null, null, null, null, null, null).ariaSort("id")).isEqualTo("ascending");
        assertThat(result(controls, null, null, "type", null, null, null).sortHref("type")).isEqualTo("/component/HR?sort=type&dir=desc");
        assertThat(result(controls, null, null, "type", "desc", null, null).sortHref("type")).isEqualTo("/component/HR?sort=type&dir=asc");
    }

    @Test
    void pageLinks_firstLastAndAroundTheCurrent_withGaps() {
        List<ControlResponseDTO> controls = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            controls.add(control(i, "HR-" + i, "HR", "REVIEW", null));
        }
        assertThat(result(controls, null, null, null, null, 5, null).pageLinks()).containsExactly(1, 0, 3, 4, 5, 6, 7, 0, 10);
        assertThat(result(controls, null, null, null, null, 1, null).pageLinks()).containsExactly(1, 2, 3, 0, 10);
        assertThat(result(List.of(), null, null, null, null, 1, null).pageLinks()).containsExactly(1);
    }

    @Test
    void nothingMatches_noRows_countersStay() {
        ComponentControlsList.Result empty = result(List.of(), null, null, null, null, null, null);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.matching()).isZero();
        assertThat(empty.from()).isZero();
        assertThat(empty.to()).isZero();
        assertThat(empty.counts()).isEqualTo(ComponentControlsList.Counts.NONE);
    }
}
