package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SimpleRoleService {

    private final UserRepository userRepository;

    // Проверка ролей
    public boolean isAdmin(String email) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && Boolean.TRUE.equals(user.get().getAdminAccess());
    }

    public boolean isFacilitator(String email) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && hasAnyRole(user.get(), Set.of("FACILITATOR", "CONTROL_OPERATOR"));
    }

    public boolean isControlOperator(String email) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && hasAnyRole(user.get(), Set.of("CONTROL_OPERATOR", "FACILITATOR"));
    }

    public boolean isSoqmLead(String email) {
        return hasRole(email, "SOQM_TEAM");
    }

    public boolean isProcessOwner(String email) {
        return hasRole(email, "PROCESS_OWNER");
    }

    // Получение пользователей по роли
    public List<UserDTO> getUsersByRole(String role) {
        // For FACILITATOR or CONTROL_OPERATOR, return users with either role (interchangeable)
        if ("FACILITATOR".equals(role) || "CONTROL_OPERATOR".equals(role)) {
            Set<String> seen = new java.util.HashSet<>();
            List<UserDTO> result = new java.util.ArrayList<>();
            for (String r : new String[]{"FACILITATOR", "CONTROL_OPERATOR"}) {
                for (User user : userRepository.findByRoleIgnoreCaseOrSecondaryRoleIgnoreCase(r, r)) {
                    if (user.getMail() != null && seen.add(user.getMail().toLowerCase())) {
                        result.add(convertToDTO(user));
                    }
                }
            }
            return result;
        }
        return userRepository.findByRoleIgnoreCaseOrSecondaryRoleIgnoreCase(role, role).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<UserDTO> getAllControlOperators() {
        return getUsersByRole("CONTROL_OPERATOR");
    }

    public List<UserDTO> getAllSoqmLeads() {
        return getUsersByRole("SOQM_TEAM");
    }

    public List<UserDTO> getAllProcessOwners() {
        return getUsersByRole("PROCESS_OWNER");
    }

    public List<UserDTO> getAllFacilitators() {
        return getUsersByRole("FACILITATOR");
    }

    // Вспомогательные методы
    private boolean hasRole(String email, String expectedRole) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && hasAnyRole(user.get(), Set.of(expectedRole));
    }

    private boolean hasAnyRole(User user, Set<String> expectedRoles) {
        if (user == null || expectedRoles == null || expectedRoles.isEmpty()) {
            return false;
        }

        Set<String> actualRoles = new LinkedHashSet<>();
        if (user.getRole() != null && !user.getRole().isBlank()) {
            actualRoles.add(user.getRole().trim().toUpperCase(Locale.ROOT));
        }
        if (user.getSecondaryRole() != null && !user.getSecondaryRole().isBlank()) {
            actualRoles.add(user.getSecondaryRole().trim().toUpperCase(Locale.ROOT));
        }

        return actualRoles.stream().anyMatch(expectedRoles::contains);
    }

    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(user.getRole());
        dto.setRole(user.getRole()); // ★ Важно: добавляем роль в DTO
        return dto;
    }
}
