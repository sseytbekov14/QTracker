package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Create Control, field by field: every field of the Control, Details and Assignment rows is either copied from the
 * source, left empty, or set anew. A field added to an entity later fails here until it is placed in one of these.
 */
class ControlNextCycleCopyTest {

    private static final Set<String> CONTROL_COPIED = Set.of("controlFrequency", "controlCategory", "controlType",
            "component", "operatedBy", "referencesToControl", "priority", "nonAuditServicesApplicability",
            "homogeneity", "controlStatus", "controlDescription", "prp");
    private static final Set<String> CONTROL_EMPTY = Set.of("soqmHeadComments", "processOwnerComments",
            "attachmentDetailsPath", "attachmentDocumentsPath", "returnToFacilitatorComment", "returnToOperatorComment",
            "returnToSoqmTeamComment", "reopenedAt",
            // the id comes from the database; the operation date is read only on Control (the Assignment's column)
            "id", "controlOperationDate");
    private static final Set<String> CONTROL_NEW = Set.of("controlId", "soqmYear", "deadline", "performanceStatus",
            "createdBy", "createdByEmail", "createdByName", "createdAt", "updatedAt", "createdFromControlId");

    private static final Set<String> DETAILS_COPIED = Set.of("processName", "homogeneity", "referencesToControl",
            "department", "processActivities", "controlOperatorReview");
    private static final Set<String> DETAILS_EMPTY = Set.of("itApplications", "otherRelatedControls",
            "controlStepsPerformed", "soqmHeadComments", "processOwnerComments");
    private static final Set<String> DETAILS_NEW = Set.of("controlId");

    private static final Set<String> ASSIGNMENT_COPIED = Set.of("facilitator", "controlOperator", "soqmLead",
            "processOwner");
    private static final Set<String> ASSIGNMENT_NEW = Set.of("controlId", "controlSharedWith", "controlOperationDate",
            "controlOperationDeadline", "nextControlOperationDate");

    private static final ControlNextCycle.Dates DATES = new ControlNextCycle.Dates(LocalDate.of(2026, 11, 10),
            LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 10), "1 OCT 2026 - 30 SEP 2027");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 15, 30);

    @Test
    void everyFieldIsPlaced() {
        assertPlaced(Control.class, CONTROL_COPIED, CONTROL_EMPTY, CONTROL_NEW);
        assertPlaced(ControlDetails.class, DETAILS_COPIED, DETAILS_EMPTY, DETAILS_NEW);
        assertPlaced(ControlAssignment.class, ASSIGNMENT_COPIED, Set.of(), ASSIGNMENT_NEW);
    }

    @Test
    void control_copiesTheControlTab_andStartsTheRestAgain() throws Exception {
        Control source = filled(new Control());
        User creator = new User();
        creator.setMail("pressed@example.test");
        creator.setDisplayName("Pressed Create");

        Control copy = ControlNextCycle.control(source, "HR-1/FY27/Central/NOV", DATES, creator, NOW);

        for (String name : CONTROL_COPIED) {
            assertThat(value(copy, name)).as(name).isEqualTo(value(source, name)).isNotNull();
        }
        for (String name : CONTROL_EMPTY) {
            assertThat(value(copy, name)).as(name).isNull();
        }
        assertThat(copy.getControlId()).isEqualTo("HR-1/FY27/Central/NOV");
        assertThat(copy.getSoqmYear()).isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(copy.getDeadline()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(copy.getPerformanceStatus()).isEqualTo("DRAFT");
        assertThat(copy.getCreatedBy()).isSameAs(creator);
        assertThat(copy.getCreatedByEmail()).isEqualTo("pressed@example.test");
        assertThat(copy.getCreatedByName()).isEqualTo("Pressed Create");
        assertThat(copy.getCreatedAt()).isEqualTo(NOW);
        assertThat(copy.getUpdatedAt()).isEqualTo(NOW);
        assertThat(copy.getCreatedFromControlId()).isEqualTo(source.getId()).isEqualTo(5L);
    }

    @Test
    void details_copiesTheDescription_notWhatEachCycleFillsIn() throws Exception {
        ControlDetails source = filled(new ControlDetails());

        ControlDetails copy = ControlNextCycle.details(source, 77L);

        for (String name : DETAILS_COPIED) {
            assertThat(value(copy, name)).as(name).isEqualTo(value(source, name)).isNotNull();
        }
        for (String name : DETAILS_EMPTY) {
            assertThat(value(copy, name)).as(name).isNull();
        }
        assertThat(copy.getControlId()).isEqualTo(77L);
        assertThat(ControlNextCycle.details(null, 78L).getProcessName()).isNull();
    }

    @Test
    void assignment_samePeople_newDates_givenSharedWith() throws Exception {
        ControlAssignment source = filled(new ControlAssignment());

        ControlAssignment copy = ControlNextCycle.assignment(source, 77L, DATES, "kept@example.test");

        for (String name : ASSIGNMENT_COPIED) {
            assertThat(value(copy, name)).as(name).isEqualTo(value(source, name)).isNotNull();
        }
        assertThat(copy.getControlId()).isEqualTo(77L);
        assertThat(copy.getControlSharedWith()).isEqualTo("kept@example.test");
        assertThat(copy.getControlOperationDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(copy.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(copy.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 12, 10));
    }

    @Test
    void notCopied_namesWhatTheConfirmationLists() {
        assertThat(ControlNextCycle.NOT_COPIED).containsExactly(
                "Other Related Controls",
                "IT Applications",
                "Control Steps Performed and Results",
                "SoQM Head/Team Comments",
                "Process Owner Comments",
                "Attachments (Details and Documents)",
                "History and Changelog",
                "Workflow statuses, return comments and dates",
                "Notifications");
    }

    @Test
    void sourceIsLeftAsItWas() throws Exception {
        Control source = filled(new Control());
        String before = source.toString();
        ControlNextCycle.control(source, "X/NOV", DATES, null, NOW);
        assertThat(source.toString()).isEqualTo(before);
        assertThat(ControlNextCycle.dates(new ControlScheduleCalculator(), ControlFrequency.MONTHLY, null)).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertPlaced(Class<?> type, Set<String> copied, Set<String> empty, Set<String> set) {
        Set<String> fields = instanceFields(type).stream().map(Field::getName).collect(Collectors.toSet());
        Set<String> placed = new HashSet<>(copied);
        placed.addAll(empty);
        placed.addAll(set);
        assertThat(placed).as(type.getSimpleName() + ": every field placed once")
                .hasSize(copied.size() + empty.size() + set.size());
        assertThat(fields).as(type.getSimpleName() + " fields").containsExactlyInAnyOrderElementsOf(placed);
    }

    private static List<Field> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers())).toList();
    }

    /** Every field given a value of its own, so a copy and an empty field can be told apart. */
    private static <T> T filled(T entity) throws Exception {
        for (Field field : instanceFields(entity.getClass())) {
            field.setAccessible(true);
            Class<?> t = field.getType();
            Object value;
            if (t == String.class) {
                value = "source " + field.getName();
            } else if (t == Long.class) {
                value = 5L;
            } else if (t == LocalDate.class) {
                value = LocalDate.of(2026, 10, 10);
            } else if (t == LocalDateTime.class) {
                value = LocalDateTime.of(2026, 10, 1, 9, 0);
            } else if (t == User.class) {
                User user = new User();
                user.setMail("creator-of-source@example.test");
                value = user;
            } else {
                throw new IllegalStateException("no test value for " + field);
            }
            field.set(entity, value);
        }
        return entity;
    }

    private static Object value(Object entity, String name) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(entity);
    }
}
