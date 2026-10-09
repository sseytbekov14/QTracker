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
    /** Shared With picker of a control: what a place there gives the user (AccessPolicy.SharedAccess) and its mark. */
    private String sharedAccess;
    private String sharedNote;
}
