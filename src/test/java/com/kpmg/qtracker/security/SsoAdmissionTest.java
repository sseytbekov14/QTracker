package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AdminAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which account a Microsoft sign-in is, and when it is refused (made-up people of example.test). */
class SsoAdmissionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T05:00:00Z"), ZoneId.of("Asia/Almaty"));

    private final List<User> users = new ArrayList<>();
    private UserRepository repository;
    private AdminAuditService audit;
    private SsoAdmission admission;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        audit = mock(AdminAuditService.class);
        // As the queries do: addresses in any case and with stray spaces
        when(repository.findByMail(anyString())).thenAnswer(call -> users.stream()
                .filter(u -> u.getMail().equalsIgnoreCase(call.<String>getArgument(0).trim())).findFirst());
        when(repository.findByEntraOid(anyString())).thenAnswer(call -> users.stream()
                .filter(u -> Objects.equals(u.getEntraOid(), call.getArgument(0))).findFirst());
        when(repository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        admission = new SsoAdmission(repository, audit, new LoginNameResolver(false, "qtracker.local"), CLOCK);
    }

    @Test
    void byTheEmailClaim_inAnyCase_andWithSpaces() {
        User anna = user(1, "Anna.Example@Example.Test");
        SsoAdmission.Decision decision = admission.admit(new SsoIdentity(null, "  anna.example@EXAMPLE.test ", null, "oid-1"));

        assertThat(decision.admitted()).isTrue();
        assertThat(decision.user()).isSameAs(anna);
        assertThat(decision.matchedBy()).isEqualTo("email");
        assertThat(anna.getLastLoginAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
    }

    @Test
    void byTheNameId_whenThereIsNoEmailClaim() {
        User anna = user(1, "anna@example.test");
        assertThat(admission.admit(new SsoIdentity("ANNA@example.test", null, null, "oid-1")).user()).isSameAs(anna);
    }

    @Test
    void byTheUpn_whenNoAddressMatches() {
        User anna = user(1, "anna.upn@example.test");
        SsoAdmission.Decision decision = admission.admit(
                new SsoIdentity("other@example.test", "other@example.test", "Anna.Upn@example.test", "oid-1"));
        assertThat(decision.user()).isSameAs(anna);
        assertThat(decision.matchedBy()).isEqualTo("upn");
    }

    @Test
    void theFirstSignIn_storesTheObjectId_andLaterOnesFindTheAccountByIt() {
        User anna = user(1, "anna@example.test");
        admission.admit(new SsoIdentity(null, "anna@example.test", null, "oid-anna"));
        assertThat(anna.getEntraOid()).isEqualTo("oid-anna");
        verify(audit).logActionWithChanges(eq("system"), eq("SSO sign-in"), eq("USER_SSO_LINKED"), isNull(),
                contains("for anna@example.test"), eq("entraOid"), eq("entraOid=-"), eq("entraOid=oid-anna"));

        // The address changed at Microsoft (a new surname): still the same account
        SsoAdmission.Decision later = admission.admit(new SsoIdentity(null, "anna.new@example.test", null, "oid-anna"));
        assertThat(later.user()).isSameAs(anna);
        assertThat(later.matchedBy()).isEqualTo("entra_oid");
    }

    @Test
    void theObjectIdAndTheAddress_ofDifferentAccounts_areRefused() {
        user(1, "anna@example.test").setEntraOid("oid-anna");
        user(2, "boris@example.test");
        SsoAdmission.Decision decision = admission.admit(new SsoIdentity(null, "boris@example.test", null, "oid-anna"));

        assertThat(decision.refusal()).isEqualTo(SsoAdmission.Reason.MISMATCH);
        verify(audit).logActionWithChanges(eq("system"), eq("SSO sign-in"), eq("USER_SSO_REFUSED"), isNull(),
                contains("refused (the Microsoft account and the address belong to different accounts) for anna@example.test"),
                eq("reason"), eq("-"), contains("reason=MISMATCH, userId=1"));
    }

    @Test
    void anAddress_linkedToAnotherMicrosoftAccount_orAnAnswerWithoutObjectId_isRefused() {
        user(1, "anna@example.test").setEntraOid("oid-anna");
        assertThat(admission.admit(new SsoIdentity(null, "anna@example.test", null, "oid-someone-else")).refusal())
                .isEqualTo(SsoAdmission.Reason.MISMATCH);
        assertThat(admission.admit(new SsoIdentity(null, "anna@example.test", null, null)).refusal())
                .isEqualTo(SsoAdmission.Reason.MISMATCH);
    }

    @Test
    void theEmailClaimAndTheNameId_ofTwoAccounts_areRefused() {
        user(1, "anna@example.test");
        user(2, "boris@example.test");
        assertThat(admission.admit(new SsoIdentity("boris@example.test", "anna@example.test", null, "oid")).refusal())
                .isEqualTo(SsoAdmission.Reason.AMBIGUOUS);
    }

    @Test
    void aDisabledAccount_isRefused_andNotSignedIn() {
        User anna = user(1, "anna@example.test");
        anna.setEnabled(false);
        SsoAdmission.Decision decision = admission.admit(new SsoIdentity(null, "anna@example.test", null, "oid-anna"));

        assertThat(decision.refusal()).isEqualTo(SsoAdmission.Reason.DISABLED);
        assertThat(anna.getLastLoginAt()).isNull();
        assertThat(anna.getEntraOid()).isNull();
    }

    @Test
    void anUnknownPerson_isRefused_andNoAccountIsCreated() {
        user(1, "anna@example.test");
        SsoAdmission.Decision decision = admission.admit(
                new SsoIdentity("new.person@example.test", "new.person@example.test", "new.person@example.test", "oid-new"));

        assertThat(decision.refusal()).isEqualTo(SsoAdmission.Reason.UNKNOWN);
        assertThat(decision.user()).isNull();
        verify(repository, never()).save(any(User.class));
        verify(audit).logActionWithChanges(eq("system"), eq("SSO sign-in"), eq("USER_SSO_REFUSED"), isNull(),
                eq("SSO sign-in refused (no account) for new.person@example.test"), eq("reason"), eq("-"),
                eq("reason=UNKNOWN, entraOid=oid-new"));
    }

    @Test
    void anAccountOnTheServiceDomain_isRefused() {
        User imported = user(1, "aexample@qtracker.local");
        imported.setEntraOid("oid-imported");
        // Even when the stored object id matches: the account still has the address the import gave it
        assertThat(admission.admit(new SsoIdentity(null, "anna@example.test", null, "oid-imported")).refusal())
                .isEqualTo(SsoAdmission.Reason.SERVICE_DOMAIN);
        assertThat(admission.admit(new SsoIdentity("AEXAMPLE@qtracker.local", null, null, "oid-imported")).refusal())
                .isEqualTo(SsoAdmission.Reason.SERVICE_DOMAIN);
    }

    private User user(long id, String mail) {
        User user = new User();
        user.setId(id);
        user.setMail(mail);
        user.setDisplayName("Person " + id);
        user.setEnabled(true);
        users.add(user);
        return user;
    }
}
