package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    private String displayName;
    private String mail;
    private String department;
    private String title;
    private String office;
    private String role;
    private String username;
}