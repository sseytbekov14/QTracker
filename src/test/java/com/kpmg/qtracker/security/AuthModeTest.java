package com.kpmg.qtracker.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exactly one way to sign in; Logout by default only with the password. */
class AuthModeTest {

    @Test
    void sso_meansNoPasswordForm_andNoLogoutByDefault() {
        AuthMode mode = new AuthMode(env("true", "false", null));
        assertThat(mode.sso()).isTrue();
        assertThat(mode.password()).isFalse();
        assertThat(mode.logout()).isFalse();
    }

    @Test
    void password_meansLogoutByDefault() {
        AuthMode mode = new AuthMode(env("false", "true", ""));
        assertThat(mode.sso()).isFalse();
        assertThat(mode.password()).isTrue();
        assertThat(mode.logout()).isTrue();
    }

    @Test
    void logout_canBeSetEitherWay() {
        assertThat(new AuthMode(env("true", "false", "true")).logout()).isTrue();
        assertThat(new AuthMode(env("false", "true", "FALSE")).logout()).isFalse();
    }

    @Test
    void nothingSet_isThePassword() {
        AuthMode mode = new AuthMode(new MockEnvironment());
        assertThat(mode.password()).isTrue();
        assertThat(mode.sso()).isFalse();
    }

    @Test
    void bothOnOrBothOff_orAWordThatIsNotABoolean_areRefused() {
        assertThat(AuthMode.refusal(env("true", "true", null)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("both true"));
        assertThat(AuthMode.refusal(env("false", "false", null)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("nobody could sign in"));
        assertThat(AuthMode.refusal(env("yes", "false", null)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("auth.sso.enabled must be true or false"));
        assertThatThrownBy(() -> new AuthMode(env("true", "true", null))).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String sso, String password, String logout) {
        MockEnvironment environment = new MockEnvironment();
        if (sso != null) {
            environment.setProperty(AuthMode.SSO, sso);
        }
        if (password != null) {
            environment.setProperty(AuthMode.PASSWORD, password);
        }
        if (logout != null) {
            environment.setProperty(AuthMode.LOGOUT, logout);
        }
        return environment;
    }
}
