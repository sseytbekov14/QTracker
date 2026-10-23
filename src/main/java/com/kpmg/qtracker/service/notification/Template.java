package com.kpmg.qtracker.service.notification;

/**
 * The notifications of QTracker (business requirements NOTIFICATIONS_v5, with the shorter texts of 2026-10-20): their
 * key, the importance of the e-mail, the type of the in-app notification, and their texts. The texts are only the
 * first content of table notification_template (V20 wrote the texts of v5, V21 these; NotificationTemplateSeeder where
 * Flyway is off); every notification reads the table, so a text changed there is used without a release.
 * <p>Placeholders: {NAME} (the person's name, NotificationRecipients.Person#name), {ROLES} (T1, T2), {CONTROL_ID},
 * {CONTROL_NAME}, {OCCURRENCE_DATE}, {DEADLINE}, {RETURNED_BY}, {LINK} (on a line of its own: "Go to Control" in the
 * e-mail, left out in-app).
 */
public enum Template {

    /** S1: the control became In Progress (Initiate, Create Control); Facilitator, Control Operator, Process Owner. */
    T1(Importance.NORMAL, "INITIATE", "The creation of the SoQM control", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been created.
            Your role: {ROLES}
            Occurrence date: {OCCURRENCE_DATE}
            Deadline: {DEADLINE}
            Please complete and submit the control before the deadline.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S2: the occurrence day (working day 0); Facilitator, Control Operator, Process Owner. */
    T2(Importance.NORMAL, "OCCURRENCE_DAY0", "The activation of the SoQM control", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} occurs today ({OCCURRENCE_DATE}).
            Your role: {ROLES}
            Deadline: {DEADLINE}
            Please complete and submit the control before the deadline.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S3, S4: working days 3 and 6 from the occurrence; the holder of the step. */
    T3(Importance.NORMAL, "REMINDER", "Control Reminder", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} is awaiting your action.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S5: the deadline day; the holder of the step. */
    T4(Importance.HIGH, "DEADLINE_TODAY", "Control Deadline Today", """
            Dear {NAME},

            The deadline for control {CONTROL_ID} {CONTROL_NAME} is today ({DEADLINE}).
            Please complete it by the end of the day.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S6: every working day after the deadline; the holder of the step. */
    T5(Importance.HIGH, "OVERDUE", "Control Deadline Notification", """
            Dear {NAME},

            The deadline for control {CONTROL_ID} {CONTROL_NAME} has passed ({DEADLINE}). The control is not completed.
            Please complete it as soon as possible.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E1: In Progress -> Review; Control Operator. */
    T6(Importance.NORMAL, "WORKFLOW_STEP", "Control Operator Review", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.
            Please review it and submit it to the SoQM Admin Team.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E2, E5: back to In Progress; Facilitator, {RETURNED_BY} the role of the step it came from. */
    T7(Importance.NORMAL, "RETURN_TO_FACILITATOR", "Control sent back to Facilitator", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the {RETURNED_BY} for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E3: to SoQM review (also again after a return); SoQM Admin Team. */
    T8(Importance.NORMAL, "WORKFLOW_STEP", "SoQM Admin Team Review", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been sent for your review.
            Please review it and submit it to the Process Owner.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E4: SoQM review -> Review; Control Operator. */
    T9(Importance.NORMAL, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the SoQM Admin Team for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E6: SoQM review -> Process Owner Review; Process Owner. */
    T10(Importance.NORMAL, "WORKFLOW_STEP", "Process Owner Review", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is ready for your review.
            Please review and complete the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E7: Process Owner Review -> Review; Control Operator. */
    T11(Importance.NORMAL, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been returned to you by the Process Owner for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E8: Completed; Facilitator, Control Operator, SoQM Admin Team, Process Owner. */
    T12(Importance.NORMAL, "COMPLETED", "SoQM control completed", """
            Dear {NAME},

            Control {CONTROL_ID} {CONTROL_NAME} has been completed.
            {LINK}

            Kind regards,
            SoQM Team""");


    public enum Importance {
        NORMAL, HIGH
    }

    private final Importance importance;
    private final String inAppType;
    private final String defaultSubject;
    private final String defaultBody;

    Template(Importance importance, String inAppType, String defaultSubject, String defaultBody) {
        this.importance = importance;
        this.inAppType = inAppType;
        this.defaultSubject = defaultSubject;
        this.defaultBody = defaultBody;
    }

    public Importance importance() {
        return importance;
    }

    /** The type of the in-app notification (table notifications), as the Notifications section labels it. */
    public String inAppType() {
        return inAppType;
    }

    /** The subject, the first content of notification_template. */
    public String defaultSubject() {
        return defaultSubject;
    }

    /** The body, the first content of notification_template. */
    public String defaultBody() {
        return defaultBody;
    }
}
