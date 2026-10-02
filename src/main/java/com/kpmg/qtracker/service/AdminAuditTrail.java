package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Audit Trail of the Admin Panel: the latest entries of admin_audit_log per event type
 * (user access, control edits, attachments, anything else), newest first. Control edits and
 * attachments also appear per control in the View Control changelog; only this list covers all controls.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditTrail {

    /** Entries loaded per event type. */
    public static final int LIMIT = 100;

    private static final Pattern FOR_MAIL = Pattern.compile("\\s+for\\s+(\\S+@\\S+)$");
    private static final Pattern FOR_USER_ID = Pattern.compile("\\s+for user id \\d+$");
    private static final Pattern MAIL_VALUE = Pattern.compile("(?:^|,\\s*)mail=([^,\\s]+)");
    private static final Pattern CREATED_USER = Pattern.compile("^Created user (\\S+)$");

    private final AdminAuditLogRepository auditLogRepository;

    public enum Group {
        USER_ACCESS("User access", "Target user"),
        CONTROL_EDIT("Control edits", "Control"),
        ATTACHMENT("Attachments", "Control"),
        OTHER("Other events", "Target");

        private final String label;
        private final String targetLabel;

        Group(String label, String targetLabel) {
            this.label = label;
            this.targetLabel = targetLabel;
        }

        public String getLabel() {
            return label;
        }

        /** Heading of the target column while only this group is shown. */
        public String getTargetLabel() {
            return targetLabel;
        }

        static Group of(String actionType) {
            String type = actionType == null ? "" : actionType;
            if (type.startsWith("USER_")) {
                return USER_ACCESS;
            }
            if ("EDIT".equals(type)) {
                return CONTROL_EDIT;
            }
            if (type.startsWith("ATTACHMENT_")) {
                return ATTACHMENT;
            }
            return OTHER;
        }
    }

    /**
     * One line of the trail. For user events the target is the user's e-mail (also after a later e-mail
     * change it is the address the entry names), for the others the business ID of the control.
     */
    public record Entry(Group group, LocalDateTime createdAt, String changedBy, String action,
                        String target, boolean targetIsUser) {
    }

    public record Trail(List<Entry> entries, Map<Group, Integer> counts) {
    }

    public Trail latest() {
        List<AdminAuditLog> logs = new ArrayList<>();
        logs.addAll(auditLogRepository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc("USER_"));
        logs.addAll(auditLogRepository.findTop100ByActionTypeOrderByCreatedAtDesc("EDIT"));
        logs.addAll(auditLogRepository.findTop100ByActionTypeStartingWithOrderByCreatedAtDesc("ATTACHMENT_"));
        logs.addAll(auditLogRepository.findOtherActionTypes(PageRequest.of(0, LIMIT)));

        List<Entry> entries = logs.stream()
                .map(AdminAuditTrail::entry)
                .sorted(Comparator.comparing(Entry::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<Group, Integer> counts = new EnumMap<>(Group.class);
        for (Group group : Group.values()) {
            counts.put(group, 0);
        }
        entries.forEach(entry -> counts.merge(entry.group(), 1, Integer::sum));
        return new Trail(entries, counts);
    }

    static Entry entry(AdminAuditLog log) {
        Group group = Group.of(log.getActionType());
        String changedBy = log.getAdminName() != null && !log.getAdminName().isBlank()
                ? log.getAdminName() : log.getAdminEmail();
        String action = log.getActionDescription() != null && !log.getActionDescription().isBlank()
                ? log.getActionDescription().trim() : log.getActionType();

        if (group != Group.USER_ACCESS) {
            return new Entry(group, log.getCreatedAt(), changedBy, action, log.getControlControlId(), false);
        }

        // The user an entry is about is named in the text the controller wrote (UserController)
        String target = null;
        Matcher forMail = FOR_MAIL.matcher(action);
        Matcher createdUser = CREATED_USER.matcher(action);
        if (forMail.find()) {
            target = forMail.group(1);
            action = action.substring(0, forMail.start());
        } else if (createdUser.find()) {
            target = createdUser.group(1);
            action = "Created user";
        } else {
            Matcher mail = MAIL_VALUE.matcher(log.getNewValues() == null ? "" : log.getNewValues());
            if (mail.find()) {
                target = mail.group(1);
            }
            action = FOR_USER_ID.matcher(action).replaceFirst("");
        }
        return new Entry(group, log.getCreatedAt(), changedBy, action, target, true);
    }
}
