package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Admin Panel for a SoQM Team member signed in with SSO: Add User needs the corporate e-mail and sets no
 * password; an imported person (service address) can still be edited until their address is moved.
 */
class SsoAdminPanelIT extends SsoTestSupport {

    private static final String CORPORATE = "Use the person's corporate e-mail: with SSO they sign in with it at Microsoft";

    @Test
    void addUser_needsTheCorporateEmail_andSetsNoPassword() throws Exception {
        MockHttpSession soqm = soqmSession();
        String page = mockMvc.perform(get("/admin/users").session(soqm))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("const SSO = true;").contains("Corporate e-mail: the user signs in with it at Microsoft");

        mockMvc.perform(post("/api/users").session(soqm).with(csrf().asHeader())
                        .param("email", "new-" + suffix() + "@qtracker.local").param("role", "USER"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(CORPORATE));

        String mail = "new-" + suffix() + "@corp.example.test";
        mockMvc.perform(post("/api/users").session(soqm).with(csrf().asHeader())
                        .param("email", mail).param("displayName", "New Person").param("role", "USER"))
                .andExpect(status().isOk());
        User created = userRepository.findByMail(mail).orElseThrow();
        assertThat(created.getPassword()).isNull();
    }

    @Test
    void anImportedPerson_canStillBeEdited_butNotMovedToAServiceAddress() throws Exception {
        MockHttpSession soqm = soqmSession();
        User imported = person("imported", AccessLevel.READ_ONLY, AccessScope.OWN);
        imported.setMail("imported" + suffix() + "@qtracker.local");
        imported = userRepository.save(imported);

        // The dialog sends the address it shows: unchanged, it is no reason to refuse the rest
        mockMvc.perform(post("/api/users/{id}/access", imported.getId()).session(soqm).with(csrf().asHeader())
                        .param("role", "USER").param("visibility", "MY").param("access", "EDIT")
                        .param("enabled", "true").param("email", imported.getMail()))
                .andExpect(status().isOk());

        User other = person("corporate", AccessLevel.PARTICIPANT, AccessScope.OWN);
        mockMvc.perform(post("/api/users/{id}/access", other.getId()).session(soqm).with(csrf().asHeader())
                        .param("enabled", "true").param("email", "moved" + suffix() + "@qtracker.local"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(CORPORATE));
    }

    private MockHttpSession soqmSession() throws Exception {
        User soqm = person("admin", AccessLevel.SOQM, AccessScope.ALL);
        return signIn("/admin/users", SamlResponses.Person.of(soqm.getMail(), newObjectId())).session();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
