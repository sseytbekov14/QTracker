package com.kpmg.qtracker.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginNameResolverTest {

    private final LoginNameResolver usernames = new LoginNameResolver(true, "qtracker.local");
    private final LoginNameResolver mailOnly = new LoginNameResolver(false, "qtracker.local");

    @Test
    void username_getsTheDomain_inLowerCaseWithoutSpaces() {
        assertThat(usernames.toMail("jdoe")).isEqualTo("jdoe@qtracker.local");
        assertThat(usernames.toMail("  JDoe ")).isEqualTo("jdoe@qtracker.local");
        assertThat(usernames.toMail("j doe")).isEqualTo("jdoe@qtracker.local");
    }

    @Test
    void email_isTakenAsItIs_inLowerCase() {
        assertThat(usernames.toMail(" JDoe@Example.Test ")).isEqualTo("jdoe@example.test");
        assertThat(mailOnly.toMail(" JDoe@Example.Test ")).isEqualTo("jdoe@example.test");
    }

    @Test
    void withoutTheSetting_aUsernameStaysWithoutDomain() {
        assertThat(mailOnly.usernameLogin()).isFalse();
        assertThat(mailOnly.toMail("jdoe")).isEqualTo("jdoe");
    }

    @Test
    void nothingTyped_staysEmpty() {
        assertThat(usernames.toMail(null)).isEmpty();
        assertThat(usernames.toMail("   ")).isEmpty();
    }

    @Test
    void theDomain_isNormalizedToo() {
        LoginNameResolver resolver = new LoginNameResolver(true, " Test.Local ");

        assertThat(resolver.domain()).isEqualTo("test.local");
        assertThat(resolver.mailOf("JDoe")).isEqualTo("jdoe@test.local");
        assertThat(mailOnly.mailOf("jdoe")).isEqualTo("jdoe@qtracker.local");
    }
}
