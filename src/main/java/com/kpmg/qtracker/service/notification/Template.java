package com.kpmg.qtracker.service.notification;

/**
 * The notifications of QTracker (business requirements NOTIFICATIONS_v5, with the shorter texts of 2026-10-20): their
 * key, the importance of the e-mail, the type of the in-app notification, and their texts. The texts are only the
 * first content of table notification_template (V20 wrote the texts of v5, V21 the shorter ones, V25 took the control's
 * description out of them, V26 renamed T3, T4 and T5, V28 says T12 "successfully"; NotificationTemplateSeeder where
 * Flyway is off); every notification reads the table, so a text changed there is used without a release.
 * <p>Placeholders: {NAME} (the person's name, NotificationRecipients.Person#name), {ROLES} (T1, T2), {CONTROL_ID},
 * {OCCURRENCE_DATE}, {DEADLINE}, {RETURNED_BY}, {LINK} (on a line of its own: "Go to Control" in the e-mail, left out
 * in-app). The texts name a control by its ID alone: the description is free text and goes on to the next cycle as it
 * is, so it can name another control (2026-11-02).
 */
public enum Template {

    /** S1: the control became In Progress (Initiate, Create Control); Facilitator, Control Operator, Process Owner. */
    T1(Importance.NORMAL, Label.ACTION_REQUIRED, "INITIATE", "The creation of the SoQM control", """
            Dear {NAME},

            Control {CONTROL_ID} has been created.
            Your role: {ROLES}
            Occurrence date: {OCCURRENCE_DATE}
            Deadline: {DEADLINE}
            Please complete and submit the control before the deadline.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S2: the occurrence day (working day 0); Facilitator, Control Operator, Process Owner. */
    T2(Importance.NORMAL, Label.ACTION_REQUIRED, "OCCURRENCE_DAY0", "The activation of the SoQM control", """
            Dear {NAME},

            Control {CONTROL_ID} occurs today ({OCCURRENCE_DATE}).
            Your role: {ROLES}
            Deadline: {DEADLINE}
            Please complete and submit the control before the deadline.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S3, S4: working days 3 and 6 from the occurrence; the holder of the step. */
    T3(Importance.NORMAL, Label.REMINDER, "REMINDER", "Awaiting your action", """
            Dear {NAME},

            Control {CONTROL_ID} is awaiting your action.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S5: the deadline day; the holder of the step. */
    T4(Importance.HIGH, Label.DUE_TODAY, "DEADLINE_TODAY", "Complete by the end of the day", """
            Dear {NAME},

            The deadline for control {CONTROL_ID} is today ({DEADLINE}).
            Please complete it by the end of the day.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** S6: every working day after the deadline; the holder of the step. */
    T5(Importance.HIGH, Label.OVERDUE, "OVERDUE", "Control not completed", """
            Dear {NAME},

            The deadline for control {CONTROL_ID} has passed ({DEADLINE}). The control is not completed.
            Please complete it as soon as possible.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E1: In Progress -> Review; Control Operator. */
    T6(Importance.NORMAL, Label.ACTION_REQUIRED, "WORKFLOW_STEP", "Control Operator Review", """
            Dear {NAME},

            Control {CONTROL_ID} has been sent for your review.
            Please review it and submit it to the SoQM Admin Team.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E2, E5: back to In Progress; Facilitator, {RETURNED_BY} the role of the step it came from. */
    T7(Importance.NORMAL, Label.ACTION_REQUIRED, "RETURN_TO_FACILITATOR", "Control sent back to Facilitator", """
            Dear {NAME},

            Control {CONTROL_ID} has been returned to you by the {RETURNED_BY} for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E3: to SoQM review (also again after a return); SoQM Admin Team. */
    T8(Importance.NORMAL, Label.ACTION_REQUIRED, "WORKFLOW_STEP", "SoQM Admin Team Review", """
            Dear {NAME},

            Control {CONTROL_ID} has been sent for your review.
            Please review it and submit it to the Process Owner.
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E4: SoQM review -> Review; Control Operator. */
    T9(Importance.NORMAL, Label.ACTION_REQUIRED, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear {NAME},

            Control {CONTROL_ID} has been returned to you by the SoQM Admin Team for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E6: SoQM review -> Process Owner Review; Process Owner. */
    T10(Importance.NORMAL, Label.ACTION_REQUIRED, "WORKFLOW_STEP", "Process Owner Review", """
            Dear {NAME},

            Control {CONTROL_ID} has been reviewed by the SoQM Admin Team and is ready for your review.
            Please review and complete the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E7: Process Owner Review -> Review; Control Operator. */
    T11(Importance.NORMAL, Label.ACTION_REQUIRED, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear {NAME},

            Control {CONTROL_ID} has been returned to you by the Process Owner for rework.
            Please review the comments and resubmit the control.
            Deadline: {DEADLINE}
            {LINK}

            Kind regards,
            SoQM Team"""),

    /** E8: Completed; Facilitator, Control Operator, SoQM Admin Team, Process Owner. */
    T12(Importance.NORMAL, Label.NONE, "COMPLETED", "SoQM control completed", """
            Dear {NAME},

            Control {CONTROL_ID} has been successfully completed.
            {LINK}

            Kind regards,
            SoQM Team""");


    public enum Importance {
        NORMAL, HIGH
    }

    /** What the e-mail asks of its reader; a reminder shows it as a label over the heading (EmailLayout). */
    public enum Label {
        ACTION_REQUIRED("Action required"), REMINDER("Reminder"), DUE_TODAY("Due today"), OVERDUE("Overdue"), NONE(null);

        private final String text;

        Label(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private final Importance importance;
    private final Label label;
    private final String inAppType;
    private final String defaultSubject;
    private final String defaultBody;

    Template(Importance importance, Label label, String inAppType, String defaultSubject, String defaultBody) {
        this.importance = importance;
        this.label = label;
        this.inAppType = inAppType;
        this.defaultSubject = defaultSubject;
        this.defaultBody = defaultBody;
    }

    public Importance importance() {
        return importance;
    }

    public Label label() {
        return label;
    }

    /** The type of the in-app notification (table notifications), as the Notifications section labels it. */
    public String inAppType() {
        return inAppType;
    }

    /**
     * The name of the step or event, the first content of notification_template.subject: the title of the e-mail and
     * of the in-app notification, and the e-mail's subject.
     */
    public String defaultSubject() {
        return defaultSubject;
    }

    /** The body, the first content of notification_template. */
    public String defaultBody() {
        return defaultBody;
    }
}
