package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final ControlAssignmentService controlAssignmentService;

    @GetMapping("/control-operators")
    public ResponseEntity<List<UserDTO>> getControlOperators() {
        List<User> users = controlAssignmentService.getUsersByRole("CONTROL_OPERATOR");
        List<UserDTO> dtos = convertUsersToDTO(users);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/soqm-leads")
    public ResponseEntity<List<UserDTO>> getSoqmLeads() {
        List<User> users = controlAssignmentService.getUsersByRole("SOQM_LEAD");
        List<UserDTO> dtos = convertUsersToDTO(users);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/process-owners")
    public ResponseEntity<List<UserDTO>> getProcessOwners() {
        List<User> users = controlAssignmentService.getUsersByRole("PROCESS_OWNER");
        List<UserDTO> dtos = convertUsersToDTO(users);
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/facilitators")
    public ResponseEntity<List<UserDTO>> getFacilitators() {
        List<User> users = controlAssignmentService.getUsersByRole("FACILITATOR");
        List<UserDTO> dtos = convertUsersToDTO(users);
        return ResponseEntity.ok(dtos);
    }

    private List<UserDTO> convertUsersToDTO(List<User> users) {
        return users.stream()
                .map(user -> {
                    UserDTO dto = new UserDTO();
                    dto.setId(user.getId());
                    dto.setDisplayName(user.getDisplayName());
                    dto.setMail(user.getMail());
                    dto.setTitle(user.getTitle());
                    dto.setRole(user.getRole());
                    return dto;
                })
                .collect(Collectors.toList());
    }
}
