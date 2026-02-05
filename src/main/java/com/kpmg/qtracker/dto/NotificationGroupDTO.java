package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class NotificationGroupDTO {
    private String dateLabel;           // "Today", "Yesterday", "Jan 22", "Jun 10"
    private List<NotificationItemDTO> notifications;
}
