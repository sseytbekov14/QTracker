package com.kpmg.qtracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session cookie as a real server sends it with SSO (prod defaults, nothing set): SameSite=None, Secure,
 * HttpOnly - so the browser sends it with Microsoft's cross-site POST to the Assertion Consumer Service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-cookie-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "file.upload.dir=target/it-uploads-sso-cookie",
        "reminders.enabled=false",
        "app.base-url=" + SsoTestSupport.SITE,
        "auth.sso.entity-id=" + SsoTestSupport.ENTITY_ID,
        "auth.sso.acs-url=" + SsoTestSupport.ACS_URL,
        "auth.sso.metadata-uri=classpath:saml/test-idp-metadata.xml"
})
@ActiveProfiles("prod")
class SsoSessionCookieIT {

    @LocalServerPort
    private int port;

    @Test
    void theSessionCookie_isSameSiteNone_secure_andHttpOnly() throws Exception {
        HttpResponse<Void> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + SsoTestSupport.SIGN_ON_PATH))
                        .GET().build(), HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).filteredOn(cookie -> cookie.startsWith("JSESSIONID=")).singleElement()
                .satisfies(cookie -> assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=None"));
    }
}
