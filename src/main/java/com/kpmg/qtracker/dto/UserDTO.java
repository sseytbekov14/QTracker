package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String displayName;
    private String mail;
    private String title;
    private String role;
    private String username;
    private Boolean enabled;
}
