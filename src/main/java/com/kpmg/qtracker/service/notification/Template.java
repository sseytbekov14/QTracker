package com.kpmg.qtracker.service.notification;

/**
 * The notifications of QTracker (business requirements NOTIFICATIONS_v5, section 5): their key, the importance of the
 * e-mail, the type of the in-app notification, and the texts of v5 word for word. The texts are only the first
 * content of table notification_template (V20, and NotificationTemplateSeeder where Flyway is off); every notification
 * reads the table, so a text changed there is used without a release.
 * <p>Placeholders: {CONTROL_ID}, {CONTROL_NAME}, {OCCURRENCE_DATE}, {DEADLINE}, {LINK}, {ROLE}, {RETURNED_BY}.
 */
public enum Template {

    /** S1: the control became In Progress (Initiate, Create Control); Facilitator, Control Operator, Process Owner. */
    T1(Importance.NORMAL, "INITIATE", "The creation of the SoQM control", """
            Dear Facilitator, Control Operator and Process Owner,

            The scheduled control {CONTROL_ID} {CONTROL_NAME} has been activated today. The occurrence date is {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.

            You can access the control using the following link: {LINK}

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** S2: the occurrence day (working day 0); Facilitator, Control Operator, Process Owner. */
    T2(Importance.NORMAL, "OCCURRENCE_DAY0", "The activation of the SoQM control", """
            Dear Facilitator, Control Operator and Process Owner,

            Please be aware that the control {CONTROL_ID} {CONTROL_NAME} occurred today on {OCCURRENCE_DATE}. Kindly complete and submit the control as required before the deadline {DEADLINE}.

            You can access the control using the following link: {LINK}

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** S3, S4: working days 3 and 6 from the occurrence; the holder of the step. */
    T3(Importance.NORMAL, "REMINDER", "Control Reminder", """
            Dear {ROLE},

            We kindly remind you that the control {CONTROL_ID} {CONTROL_NAME} is pending your action. You can access the control using the following link: {LINK}

            The deadline of the control is {DEADLINE}.

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** S5: the deadline day; the holder of the step. */
    T4(Importance.HIGH, "DEADLINE_TODAY", "Control Deadline Today", """
            Dear {ROLE},

            Please be aware that the deadline for the following control is today, {DEADLINE}:
            {CONTROL_ID} {CONTROL_NAME}

            Please ensure this control is completed by the end of the day. You can access and complete the control using the link below:
            {LINK}

            If you have any questions or require assistance, please let us know.

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** S6: every working day after the deadline; the holder of the step. */
    T5(Importance.HIGH, "OVERDUE", "Control Deadline Notification", """
            Dear {ROLE},

            Our records indicate that the control completion deadline ({DEADLINE}) has passed.
            The following control remains incomplete: {CONTROL_ID} {CONTROL_NAME}

            Please ensure this control is completed as soon as possible. You can access and complete the control using the link below:
            {LINK}

            If you have any questions or require assistance, please let us know.

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** E1: In Progress -> Review; Control Operator. */
    T6(Importance.NORMAL, "WORKFLOW_STEP", "Control Operator Review", """
            Dear Control Operator,

            The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the 'SoQM Admin Team review' using the link below:
            {LINK}

            The deadline of the control is {DEADLINE}.

            If you have any questions or require assistance, please let us know.

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** E2, E5: back to In Progress; Facilitator, {RETURNED_BY} the role of the step it came from. */
    T7(Importance.NORMAL, "RETURN_TO_FACILITATOR", "Control sent back to Facilitator", """
            Dear Facilitator,

            The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the {RETURNED_BY} and requires some adjustments.

            Please click the link below to view the comments and feedback for this control:
            {LINK}

            The deadline of the control is {DEADLINE}.

            Thank you for your cooperation.
            Kind regards,
            SoQM Team"""),

    /** E3: to SoQM review (also again after a return); SoQM Admin Team. */
    T8(Importance.NORMAL, "WORKFLOW_STEP", "SoQM Admin Team Review", """
            Dear SoQM Admin Team,

            The control {CONTROL_ID} {CONTROL_NAME} has been sent for your review. Please kindly review and submit the control for the 'Process Owner review' using the link below:
            {LINK}

            Thank you,
            Kind regards,
            SoQM Team"""),

    /** E4: SoQM review -> Review; Control Operator. */
    T9(Importance.NORMAL, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear Control Operator,

            The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and requires some adjustments.

            Please click the link below to view the comments and feedback for this control:
            {LINK}

            The deadline of the control is {DEADLINE}.

            Thank you for your cooperation.
            Kind regards,
            SoQM Team"""),

    /** E6: SoQM review -> Process Owner Review; Process Owner. */
    T10(Importance.NORMAL, "WORKFLOW_STEP", "Process Owner Review", """
            Dear Process Owner,

            The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the SoQM Admin Team and is now ready for your review.

            Please kindly review and complete the control using the following link:
            {LINK}

            The deadline of the control is {DEADLINE}.

            Thank you for your attention.
            Kind regards,
            SoQM Team"""),

    /** E7: Process Owner Review -> Review; Control Operator. */
    T11(Importance.NORMAL, "RETURN_TO_OPERATOR", "Control sent back to Control Operator", """
            Dear Control Operator,

            The control {CONTROL_ID} {CONTROL_NAME} has been reviewed by the Process Owner and requires some adjustments.

            Please click the link below to view the comments and feedback for this control:
            {LINK}

            The deadline of the control is {DEADLINE}.

            Thank you for your cooperation.
            Kind regards,
            SoQM Team"""),

    /** E8: Completed; Facilitator, Control Operator, SoQM Admin Team, Process Owner. No {LINK}: the e-mail gets the button. */
    T12(Importance.NORMAL, "COMPLETED", "SoQM control completed", """
            Dear Facilitator, Control Operator, SoQM Admin Team and Process Owner,

            Please note that the control {CONTROL_ID} {CONTROL_NAME} has been successfully completed in the system.

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

    /** The subject of v5, the first content of notification_template. */
    public String defaultSubject() {
        return defaultSubject;
    }

    /** The body of v5, the first content of notification_template. */
    public String defaultBody() {
        return defaultBody;
    }
}
