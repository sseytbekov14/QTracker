package com.kpmg.qtracker.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The prod profile with SSO as it is deployed, against the made-up identity provider of
 * src/test/resources/saml (its metadata file stands for SAML_METADATA_URI). Every SSO test class extends this,
 * so they share one application context and one database. The site is https://qtracker.test.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "file.upload.dir=target/it-uploads-sso",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "app.base-url=" + SsoTestSupport.SITE,
        "auth.sso.entity-id=" + SsoTestSupport.ENTITY_ID,
        "auth.sso.acs-url=" + SsoTestSupport.ACS_URL,
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
abstract class SsoTestSupport {

    static final String SITE = "https://qtracker.test";
    static final String ENTITY_ID = SITE + "/saml/qtracker";
    static final String ACS_PATH = "/login/saml2/sso/entra";
    static final String ACS_URL = SITE + ACS_PATH;
    static final String SIGN_ON_PATH = "/saml2/authenticate/entra";
    static final String IDP_ENTITY_ID = "https://idp.qtracker.test/tests-only";
    static final String IDP_SSO_URL = IDP_ENTITY_ID + "/saml2";
}
