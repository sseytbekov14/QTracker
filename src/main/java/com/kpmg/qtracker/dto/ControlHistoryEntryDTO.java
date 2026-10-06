package com.kpmg.qtracker.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;

@Data
public class ControlHistoryEntryDTO {
    private String eventName;
    private String actorName;
    private String actorEmail;
    private String eventDetails;
    private LocalDateTime createdAt;
    private String tableType;
    private List<FieldChangeDTO> fieldChanges;

    // Workflow moves only: the statuses, the role whose step it was, whether SoQM made it for the people
    // assigned to that step, and who they were (names with e-mails)
    private String fromStep;
    private String toStep;
    private String actedAs;
    private boolean onBehalf;
    private String assignedPerformer;

    public String getFormattedTime() {
        if (createdAt == null) return "";
        return createdAt.format(DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", Locale.US));
    }
}
