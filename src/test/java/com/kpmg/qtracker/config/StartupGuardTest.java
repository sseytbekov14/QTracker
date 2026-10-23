package com.kpmg.qtracker.config;

import com.kpmg.qtracker.QtrackerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The start is refused without an explicit profile, with two deployment profiles or an unusable sign-in. */
class StartupGuardTest {

    @Test
    void noProfile_isRefused() {
        assertThat(StartupGuard.refusal(new MockEnvironment()))
                .hasValueSatisfying(reason -> assertThat(reason).contains("SPRING_PROFILES_ACTIVE=dev, stage or prod"));
        assertThat(StartupGuard.refusal(profiles("ssodev")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("(active: ssodev)"));
    }

    @Test
    void oneDeploymentProfile_orTheTestProfile_starts() {
        assertThat(StartupGuard.refusal(profiles("dev"))).isEmpty();
        assertThat(StartupGuard.refusal(profiles("stage"))).isEmpty();
        assertThat(StartupGuard.refusal(profiles("prod"))).isEmpty();
        assertThat(StartupGuard.refusal(profiles("test"))).isEmpty();
        assertThat(StartupGuard.refusal(profiles("test", "dev"))).isEmpty();
    }

    @Test
    void twoDeploymentProfiles_areRefused() {
        assertThat(StartupGuard.refusal(profiles("dev", "prod")))
                .hasValueSatisfying(reason -> assertThat(reason).contains("only one of the profiles"));
    }

    @Test
    void anUnusableSignIn_isRefused() {
        MockEnvironment both = profiles("prod")
                .withProperty("auth.sso.enabled", "true").withProperty("auth.password.enabled", "true");
        assertThat(StartupGuard.refusal(both)).hasValueSatisfying(reason -> assertThat(reason).contains("both true"));
    }

    /** The real start: SpringApplication without a profile stops before any bean is made. */
    @Test
    void theApplication_doesNotStartWithoutAProfile() {
        assertThatThrownBy(() -> QtrackerApplication.runCommand("--spring.main.banner-mode=off"))
                .hasMessageContaining("QTracker does not start: choose the profile explicitly");
    }

    private static MockEnvironment profiles(String... names) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(names);
        return environment;
    }
}
