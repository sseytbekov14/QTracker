package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
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
        return hasRole(email, "FACILITATOR");
    }

    public boolean isControlOperator(String email) {
        return hasRole(email, "CONTROL_OPERATOR");
    }

    public boolean isSoqmLead(String email) {
        return hasRole(email, "SOQM_LEAD");
    }

    public boolean isProcessOwner(String email) {
        return hasRole(email, "PROCESS_OWNER");
    }

    // Получение пользователей по роли
    public List<UserDTO> getUsersByRole(String role) {
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
