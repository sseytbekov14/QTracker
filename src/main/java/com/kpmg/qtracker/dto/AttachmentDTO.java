package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AttachmentDTO {
    private String name;      // File name or link text
    private String url;       // File path or external link
    private String type;      // "file" or "link"
}
