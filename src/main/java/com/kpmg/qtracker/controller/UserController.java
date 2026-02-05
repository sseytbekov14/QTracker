package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping
    public List<UserDTO> getAllUsers() {
        return userService.getAllUsers().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/{email}")
    public ResponseEntity<UserDTO> getUserByEmail(@PathVariable String email) {
        return userService.getUserByEmail(email)
                .map(user -> ResponseEntity.ok(convertToDTO(user)))
                .orElse(ResponseEntity.notFound().build());
    }


    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setDepartment(user.getDepartment());
        dto.setTitle(user.getTitle());
        dto.setOffice(user.getOffice());
        return dto;
    }
}