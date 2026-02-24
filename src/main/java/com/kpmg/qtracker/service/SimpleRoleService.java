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
        return hasRole(email, "ADMIN");
    }

    public boolean isFacilitator(String email) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && ("FACILITATOR".equals(user.get().getRole()) || "CONTROL_OPERATOR".equals(user.get().getRole()));
    }

    public boolean isControlOperator(String email) {
        Optional<User> user = userRepository.findByMail(email);
        return user.isPresent() && ("CONTROL_OPERATOR".equals(user.get().getRole()) || "FACILITATOR".equals(user.get().getRole()));
    }

    public boolean isSoqmLead(String email) {
        return hasRole(email, "SOQM_LEAD");
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
                for (User user : userRepository.findByRole(r)) {
                    if (user.getMail() != null && seen.add(user.getMail().toLowerCase())) {
                        result.add(convertToDTO(user));
                    }
                }
            }
            return result;
        }
        return userRepository.findByRole(role).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<UserDTO> getAllControlOperators() {
        return getUsersByRole("CONTROL_OPERATOR");
    }

    public List<UserDTO> getAllSoqmLeads() {
        return getUsersByRole("SOQM_LEAD");
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
        return user.isPresent() && expectedRole.equals(user.get().getRole());
    }

    private UserDTO convertToDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(user.getTitle());
        dto.setRole(user.getRole()); // ★ Важно: добавляем роль в DTO
        return dto;
    }
}
