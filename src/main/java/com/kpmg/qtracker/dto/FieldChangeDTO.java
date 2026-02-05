package com.kpmg.qtracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldChangeDTO {
    private String field;
    private String oldValue;
    private String newValue;
}
