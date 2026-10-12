package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The three SAML settings must be given, explicitly and in the shape Entra and Spring expect. */
class SamlConfigTest {

    private static final String ENTITY = "https://qtracker.example.test/saml";
    private static final String ACS = "https://qtracker.example.test/login/saml2/sso/entra";
    private static final String METADATA = "https://login.example.test/tenant/federationmetadata.xml";

    @Test
    void allThree_given_areUsable() {
        assertThat(SamlConfig.problems(ENTITY, ACS, METADATA)).isEmpty();
    }

    @Test
    void missingSettings_areNamed() {
        assertThat(SamlConfig.problems("", " ", null)).hasSize(3)
                .anySatisfy(p -> assertThat(p).startsWith("SAML_ENTITY_ID is not set"))
                .anySatisfy(p -> assertThat(p).startsWith("SAML_ACS_URL is not set"))
                .anySatisfy(p -> assertThat(p).startsWith("SAML_METADATA_URI is not set"));
    }

    @Test
    void springsTemplateEntityId_isRefused() {
        assertThat(SamlConfig.problems("{baseUrl}/saml2/service-provider-metadata/{registrationId}", ACS, METADATA))
                .containsExactly("SAML_ENTITY_ID must be the Identifier itself, not a template");
    }

    @Test
    void theReplyUrl_mustBeAbsolute_andEndAtTheEntraPath() {
        assertThat(SamlConfig.problems(ENTITY, "/login/saml2/sso/entra", METADATA)).singleElement()
                .satisfies(p -> assertThat(p).contains("absolute"));
        assertThat(SamlConfig.problems(ENTITY, "https://qtracker.example.test/saml/acs", METADATA)).singleElement()
                .satisfies(p -> assertThat(p).contains("must end with /login/saml2/sso/entra"));
    }

    @Test
    void theReplyUrl_offTheSiteAddress_isWarned() {
        assertThat(SamlConfig.siteMismatch("https://qtracker.example.test", ACS)).isEmpty();
        assertThat(SamlConfig.siteMismatch("https://other.example.test", ACS)).isPresent();
        assertThat(SamlConfig.siteMismatch("", ACS)).hasValueSatisfying(w -> assertThat(w).contains("APP_BASE_URL"));
    }
}
