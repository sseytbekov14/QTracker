package com.kpmg.qtracker.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The address a page with a wrong value opens again: the parameter left out, the others kept as they came. */
class BadPageAddressHandlerTest {

    @Test
    void theWrongParameter_isLeftOut_theOthersKept() {
        assertThat(BadPageAddressHandler.withoutParameter("/admin/audit", "page=abc", "page")).isEqualTo("/admin/audit");
        assertThat(BadPageAddressHandler.withoutParameter("/admin/audit", null, "page")).isEqualTo("/admin/audit");
        assertThat(BadPageAddressHandler.withoutParameter("/component/ALL", "q=HR%20E2E&page=abc&status=REVIEW", "page"))
                .isEqualTo("/component/ALL?q=HR%20E2E&status=REVIEW");
        // every value of it, also written encoded; an empty pair dropped
        assertThat(BadPageAddressHandler.withoutParameter("/admin/emails", "from=bad&&%66rom=x&to=2026-11-10", "from"))
                .isEqualTo("/admin/emails?to=2026-11-10");
        // a {...} or a broken % stays as it came
        assertThat(BadPageAddressHandler.withoutParameter("/kdn", "q={x}&size=a&q2=%zz", "size")).isEqualTo("/kdn?q={x}&q2=%zz");
    }

    @Test
    void anAddressThatIsNotTheApplications_isNotOpened() {
        assertThat(BadPageAddressHandler.withoutParameter("//evil.example/x", "page=a", "page")).isNull();
        assertThat(BadPageAddressHandler.withoutParameter("evil.example", "page=a", "page")).isNull();
        assertThat(BadPageAddressHandler.withoutParameter("/\\evil.example", "page=a", "page")).isNull();
        assertThat(BadPageAddressHandler.withoutParameter(null, "page=a", "page")).isNull();
    }
}
