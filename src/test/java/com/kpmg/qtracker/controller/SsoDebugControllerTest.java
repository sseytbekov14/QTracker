package com.kpmg.qtracker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** The debug endpoint switched on in prod is a WARN at start; elsewhere it is not. */
class SsoDebugControllerTest {

    @Test
    void inProd_aWarning() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThat(SsoDebugController.prodWarning(prod))
                .hasValueSatisfying(warning -> assertThat(warning).contains("AUTH_SSO_DEBUG_ENDPOINT is on in prod"));
    }

    @Test
    void onStage_noWarning() {
        MockEnvironment stage = new MockEnvironment();
        stage.setActiveProfiles("stage");
        assertThat(SsoDebugController.prodWarning(stage)).isEmpty();
    }
}
