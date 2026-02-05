package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role")
    private String role;

    @Column(name = "displayname")
    private String displayName;

    @Column(unique = true, name = "mail")
    private String mail;

    private String department;
    private String title;
    private String office;
    private Boolean enabled;

    @Column(name = "username", unique = true)
    private String username;

    @Column(name = "password")
    private String password = "P@ssw0rd";
}