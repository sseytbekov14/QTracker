package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.UserPrincipal;
import com.kpmg.qtracker.security.UserPrincipalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Тесты бизнес-логики: Фильтрация полей по ролям и проверка прав на изменение полей.
 *
 * Покрывает AuthorizationPolicy.filterReadableFields() и validateEditableFields() —
 * гарантирует, что роли не видят чужие данные и не могут записать запрещённые поля.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationPolicy — фильтрация и валидация полей по ролям")
class AuthorizationPolicyFieldFilterTest {

    @Mock private ControlPermissionService controlPermissionService;
    @Mock private IControlService controlService;
    @Mock private UserService userService;
    @Mock private UserPrincipalService userPrincipalService;
    @Mock private ControlAssignmentService controlAssignmentService;
    @Mock private ControlDetailsService controlDetailsService;

    private AuthorizationPolicy authPolicy;

    @BeforeEach
    void setUp() {
        authPolicy = new AuthorizationPolicy(
                controlPermissionService,
                controlService,
                userService,
                userPrincipalService,
                controlAssignmentService,
                controlDetailsService
        );
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. filterReadableFields — что видит каждая роль
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("filterReadableFields — фильтрация полей DTO по ролям")
    class FilterReadableFieldsTests {

        @Test
        @DisplayName("SOQM_TEAM видит все поля, включая soqmHeadComments")
        void soqmTeamSeesAllFields() {
            UserPrincipal principal = makePrincipal("soqm@test.com", "SOQM_TEAM");
            ControlDetailsDTO dto = fullDetailsDto();

            ControlDetailsDTO result = authPolicy.filterReadableFields(dto, principal);

            assertThat(result.getSoqmHeadComments()).isEqualTo("soqm-secret");
            assertThat(result.getProcessOwnerComments()).isEqualTo("po-comment");
            assertThat(result.getControlStepsPerformed()).isEqualTo("steps");
        }

        @Test
        @DisplayName("PROCESS_OWNER видит все поля, включая soqmHeadComments")
        void processOwnerSeesAllFields() {
            UserPrincipal principal = makePrincipal("po@test.com", "PROCESS_OWNER");
            ControlDetailsDTO dto = fullDetailsDto();

            ControlDetailsDTO result = authPolicy.filterReadableFields(dto, principal);

            assertThat(result.getSoqmHeadComments()).isEqualTo("soqm-secret");
        }

        @Test
        @DisplayName("FACILITATOR НЕ видит soqmHeadComments — они скрываются")
        void facilitatorCannotSeeSoqmComments() {
            UserPrincipal principal = makePrincipal("fac@test.com", "FACILITATOR");
            ControlDetailsDTO dto = fullDetailsDto();

            ControlDetailsDTO result = authPolicy.filterReadableFields(dto, principal);

            assertThat(result.getSoqmHeadComments()).isNull();
            // Но обычные поля видит
            assertThat(result.getControlStepsPerformed()).isEqualTo("steps");
            assertThat(result.getProcessName()).isEqualTo("process-name");
        }

        @Test
        @DisplayName("CONTROL_OPERATOR НЕ видит soqmHeadComments, но видит processOwnerComments")
        void controlOperatorSeesProcessOwnerCommentsButNotSoqm() {
            UserPrincipal principal = makePrincipal("co@test.com", "CONTROL_OPERATOR");
            ControlDetailsDTO dto = fullDetailsDto();

            ControlDetailsDTO result = authPolicy.filterReadableFields(dto, principal);

            assertThat(result.getSoqmHeadComments()).isNull();
            assertThat(result.getProcessOwnerComments()).isEqualTo("po-comment");
        }

        @Test
        @DisplayName("filterReadableFields с null DTO возвращает null без ошибки")
        void filterWithNullDtoReturnsNull() {
            UserPrincipal principal = makePrincipal("fac@test.com", "FACILITATOR");

            ControlDetailsDTO result = authPolicy.filterReadableFields(null, principal);

            assertThat(result).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. validateEditableFields — кто что может записать
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("validateEditableFields — защита полей от несанкционированного изменения")
    class ValidateEditableFieldsTests {

        @Test
        @DisplayName("FACILITATOR не может изменить soqmHeadComments — бросается AccessDeniedException")
        void facilitatorCannotChangeSoqmComments() {
            UserPrincipal principal = makePrincipal("fac@test.com", "FACILITATOR");

            ControlDetailsDTO existing = new ControlDetailsDTO();
            existing.setControlId(1L);
            existing.setSoqmHeadComments("original-soqm-comment");

            ControlDetailsDTO incoming = new ControlDetailsDTO();
            incoming.setControlId(1L);
            incoming.setSoqmHeadComments("HACKED soqm comment!"); // попытка изменить

            when(controlDetailsService.getDetailsByControlId(1L)).thenReturn(existing);

            assertThatThrownBy(() -> authPolicy.validateEditableFields(incoming, principal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("FACILITATOR не может изменить processOwnerComments")
        void facilitatorCannotChangeProcessOwnerComments() {
            UserPrincipal principal = makePrincipal("fac@test.com", "FACILITATOR");

            ControlDetailsDTO existing = new ControlDetailsDTO();
            existing.setControlId(1L);
            existing.setProcessOwnerComments("original-po");

            ControlDetailsDTO incoming = new ControlDetailsDTO();
            incoming.setControlId(1L);
            incoming.setProcessOwnerComments("CHANGED po comment!");

            when(controlDetailsService.getDetailsByControlId(1L)).thenReturn(existing);

            assertThatThrownBy(() -> authPolicy.validateEditableFields(incoming, principal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("CONTROL_OPERATOR не может изменить soqmHeadComments")
        void controlOperatorCannotChangeSoqmComments() {
            UserPrincipal principal = makePrincipal("co@test.com", "CONTROL_OPERATOR");

            ControlDetailsDTO existing = new ControlDetailsDTO();
            existing.setControlId(1L);
            existing.setSoqmHeadComments("original");

            ControlDetailsDTO incoming = new ControlDetailsDTO();
            incoming.setControlId(1L);
            incoming.setSoqmHeadComments("HACKED!");

            when(controlDetailsService.getDetailsByControlId(1L)).thenReturn(existing);

            assertThatThrownBy(() -> authPolicy.validateEditableFields(incoming, principal))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("SOQM_TEAM может изменить любые поля без ошибки")
        void soqmTeamCanChangeAnyField() {
            UserPrincipal principal = makePrincipal("soqm@test.com", "SOQM_TEAM");

            ControlDetailsDTO incoming = new ControlDetailsDTO();
            incoming.setSoqmHeadComments("new soqm comment");
            incoming.setProcessOwnerComments("new po comment");

            // Не должно бросать исключение
            authPolicy.validateEditableFields(incoming, principal);
        }

        @Test
        @DisplayName("FACILITATOR может изменить controlStepsPerformed без ошибки")
        void facilitatorCanChangeStepsPerformed() {
            UserPrincipal principal = makePrincipal("fac@test.com", "FACILITATOR");

            ControlDetailsDTO existing = new ControlDetailsDTO();
            existing.setControlId(1L);
            existing.setSoqmHeadComments("same soqm"); // не меняем
            existing.setProcessOwnerComments("same po"); // не меняем

            ControlDetailsDTO incoming = new ControlDetailsDTO();
            incoming.setControlId(1L);
            incoming.setControlStepsPerformed("updated steps!"); // это разрешено
            incoming.setSoqmHeadComments("same soqm");
            incoming.setProcessOwnerComments("same po");

            when(controlDetailsService.getDetailsByControlId(1L)).thenReturn(existing);

            // Не должно бросать исключение
            authPolicy.validateEditableFields(incoming, principal);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. hasRole — корректное определение ролей
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("hasRole — распознавание ролей с ROLE_ префиксом и без")
    class HasRoleTests {

        @Test
        @DisplayName("hasRole распознаёт роль с префиксом ROLE_FACILITATOR")
        void recognizesRoleWithPrefix() {
            UserPrincipal principal = new UserPrincipal(1L, "user@test.com", Set.of("ROLE_FACILITATOR"));

            assertThat(authPolicy.hasRole(principal, "FACILITATOR")).isTrue();
        }

        @Test
        @DisplayName("hasRole распознаёт роль без префикса FACILITATOR")
        void recognizesRoleWithoutPrefix() {
            UserPrincipal principal = new UserPrincipal(1L, "user@test.com", Set.of("FACILITATOR"));

            assertThat(authPolicy.hasRole(principal, "FACILITATOR")).isTrue();
        }

        @Test
        @DisplayName("hasRole возвращает false для несуществующей роли")
        void returnsFalseForWrongRole() {
            UserPrincipal principal = new UserPrincipal(1L, "user@test.com", Set.of("FACILITATOR"));

            assertThat(authPolicy.hasRole(principal, "SOQM_TEAM")).isFalse();
        }

        @Test
        @DisplayName("hasRole с null principal возвращает false")
        void returnsFalseForNullPrincipal() {
            assertThat(authPolicy.hasRole(null, "FACILITATOR")).isFalse();
        }
    }

    // ─── Вспомогательные методы ─────────────────────────────────────────

    private UserPrincipal makePrincipal(String email, String role) {
        return new UserPrincipal(1L, email, Set.of(role));
    }

    private ControlDetailsDTO fullDetailsDto() {
        ControlDetailsDTO dto = new ControlDetailsDTO();
        dto.setControlId(1L);
        dto.setProcessName("process-name");
        dto.setControlStepsPerformed("steps");
        dto.setSoqmHeadComments("soqm-secret");
        dto.setProcessOwnerComments("po-comment");
        return dto;
    }
}
