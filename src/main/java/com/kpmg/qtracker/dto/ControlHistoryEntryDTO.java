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

    public String getFormattedTime() {
        if (createdAt == null) return "";
        return createdAt.format(DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", Locale.US));
    }
}
