package com.kpmg.qtracker.security;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.service.AdminAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a person Microsoft signed in gets into QTracker. Accounts are never created here: the person must be in
 * users already (Admin Panel, user import), active and with a real address (not the service domain the import
 * gives, auth.username-domain).
 *
 * <p>The account is found by the Entra object id once it is known (stored at the first sign-in), else by the
 * e-mail claim and the NameID (trimmed, any case), then by the UPN. The object id and the address must not point
 * at different people (or the address at an account linked to another Microsoft account): refused. Every refusal
 * is a WARN with the user id and the reason (no address) and an entry in the audit trail; the person only sees
 * "Access is not granted".
 */
@Service
public class SsoAdmission {

    public static final String AUDIT_ACTOR = "system";
    public static final String AUDIT_ACTOR_NAME = "SSO sign-in";
    public static final String AUDIT_REFUSED = "USER_SSO_REFUSED";
    public static final String AUDIT_LINKED = "USER_SSO_LINKED";

    private static final Logger logger = LoggerFactory.getLogger(SsoAdmission.class);

    public enum Reason {
        UNKNOWN("no account"),
        AMBIGUOUS("the address matches more than one account"),
        MISMATCH("the Microsoft account and the address belong to different accounts"),
        DISABLED("account disabled"),
        SERVICE_DOMAIN("the account has a service address, not a corporate one");

        private final String words;

        Reason(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    /** In (the account, and how it was found) or out (why, and the account when there is one). */
    public record Decision(User user, String matchedBy, Reason refusal) {

        public boolean admitted() {
            return refusal == null;
        }
    }

    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final LoginNameResolver loginNames;
    private final Clock clock;

    public SsoAdmission(UserRepository userRepository, AdminAuditService auditService,
                        LoginNameResolver loginNames, Clock clock) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.loginNames = loginNames;
        this.clock = clock;
    }

    /**
     * Decides, and records: an admitted person gets last_login_at (and the object id at the first sign-in, with an
     * audit entry); a refusal gets its audit entry. Never throws for a refusal, so the entries are kept.
     */
    @Transactional
    public Decision admit(SsoIdentity identity) {
        Decision decision = decide(identity);
        if (!decision.admitted()) {
            refused(identity, decision);
            return decision;
        }
        User user = decision.user();
        if (user.getEntraOid() == null && identity.objectId() != null) {
            user.setEntraOid(identity.objectId());
            auditService.logActionWithChanges(AUDIT_ACTOR, AUDIT_ACTOR_NAME, AUDIT_LINKED, null,
                    "Linked the Microsoft account at the first SSO sign-in for " + user.getMail(),
                    "entraOid", "entraOid=-", "entraOid=" + identity.objectId());
        }
        user.setLastLoginAt(LocalDateTime.now(clock));
        userRepository.save(user);
        logger.info("SSO sign-in: user={} matchedBy={}", user.getId(), decision.matchedBy());
        return decision;
    }

    Decision decide(SsoIdentity identity) {
        Optional<User> byObjectId = identity.objectId() == null
                ? Optional.empty() : userRepository.findByEntraOid(identity.objectId());

        Map<Long, User> byAddress = new LinkedHashMap<>();
        for (String address : addresses(identity)) {
            userRepository.findByMail(address).ifPresent(user -> byAddress.putIfAbsent(user.getId(), user));
        }
        if (byAddress.size() > 1) {
            return refusal(Reason.AMBIGUOUS, byObjectId.orElse(null));
        }
        String matchedBy = "email";
        Optional<User> candidate = byAddress.values().stream().findFirst();
        if (candidate.isEmpty() && identity.upn() != null) {
            candidate = userRepository.findByMail(identity.upn());
            matchedBy = "upn";
        }

        User user;
        if (byObjectId.isPresent()) {
            if (candidate.isPresent() && !candidate.get().getId().equals(byObjectId.get().getId())) {
                return refusal(Reason.MISMATCH, byObjectId.get());
            }
            user = byObjectId.get();
            matchedBy = "entra_oid";
        } else if (candidate.isPresent()) {
            user = candidate.get();
            // Linked to another Microsoft account, or the answer has no object id to compare with
            if (user.getEntraOid() != null && !user.getEntraOid().equals(identity.objectId())) {
                return refusal(Reason.MISMATCH, user);
            }
        } else {
            return refusal(Reason.UNKNOWN, null);
        }

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return refusal(Reason.DISABLED, user);
        }
        if (loginNames.isServiceAddress(user.getMail())) {
            return refusal(Reason.SERVICE_DOMAIN, user);
        }
        return new Decision(user, matchedBy, null);
    }

    /** The e-mail claim, and the NameID when it is an address: trimmed, lower case, without repeats. */
    private static Set<String> addresses(SsoIdentity identity) {
        Set<String> addresses = new LinkedHashSet<>();
        for (String value : new String[]{identity.email(), identity.nameId()}) {
            if (value != null && value.contains("@")) {
                addresses.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return addresses;
    }

    private static Decision refusal(Reason reason, User user) {
        return new Decision(user, null, reason);
    }

    private void refused(SsoIdentity identity, Decision decision) {
        User user = decision.user();
        logger.warn("SSO sign-in refused: user={} reason={}", user == null ? "-" : user.getId(), decision.refusal());
        // The audit trail names the account, or for an unknown person the address Microsoft gave
        String target = user != null ? user.getMail() : firstNonNull(identity.email(), identity.nameId(), identity.upn());
        auditService.logActionWithChanges(AUDIT_ACTOR, AUDIT_ACTOR_NAME, AUDIT_REFUSED, null,
                "SSO sign-in refused (" + decision.refusal().words() + ") for " + (target == null ? "-" : target),
                "reason", "-",
                "reason=" + decision.refusal() + (user == null ? "" : ", userId=" + user.getId())
                        + (identity.objectId() == null ? "" : ", entraOid=" + identity.objectId()));
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
