package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** With SSO the session cookie defaults to SameSite=None, Secure, HttpOnly; given values win. */
class SessionCookieDefaultsTest {

    @Test
    void withSso_andNothingGiven_noneSecureHttpOnly() {
        MockEnvironment environment = new MockEnvironment().withProperty("auth.sso.enabled", "true");
        new SessionCookieDefaults().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SessionCookieDefaults.SAME_SITE)).isEqualTo("none");
        assertThat(environment.getProperty(SessionCookieDefaults.SECURE)).isEqualTo("true");
        assertThat(environment.getProperty(SessionCookieDefaults.HTTP_ONLY)).isEqualTo("true");
    }

    @Test
    void givenValues_win_andAnEmptyOneCountsAsNotGiven() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("auth.sso.enabled", "true")
                .withProperty(SessionCookieDefaults.SAME_SITE, "lax")
                .withProperty(SessionCookieDefaults.SECURE, "");
        new SessionCookieDefaults().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(SessionCookieDefaults.SAME_SITE)).isEqualTo("lax");
        assertThat(environment.getProperty(SessionCookieDefaults.SECURE)).isEqualTo("true");
    }

    @Test
    void withoutSso_nothingIsAdded() {
        MockEnvironment environment = new MockEnvironment().withProperty("auth.sso.enabled", "false");
        new SessionCookieDefaults().postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(SessionCookieDefaults.SOURCE)).isFalse();
        assertThat(environment.getProperty(SessionCookieDefaults.SAME_SITE)).isNull();
    }
}
