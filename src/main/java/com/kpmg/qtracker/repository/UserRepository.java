package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * The user of an address, ignoring case and surrounding spaces: people type addresses in any case, and assignment
     * lists may keep a spelling that differs from the stored user. Two users may hold one address in another case
     * (users.mail is unique only as written): then the active one, else the oldest - never a failure.
     */
    default Optional<User> findByMail(String mail) {
        if (mail == null || mail.isBlank()) {
            return Optional.empty();
        }
        List<User> users = findAllByMail(mail);
        return users.stream().filter(user -> Boolean.TRUE.equals(user.getEnabled())).findFirst()
                .or(() -> users.stream().findFirst());
    }

    /** Every user of an address, ignoring case and surrounding spaces, the oldest first. */
    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.mail)) = LOWER(TRIM(:mail)) ORDER BY u.id")
    List<User> findAllByMail(@Param("mail") String mail);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u "
            + "WHERE LOWER(u.mail) = LOWER(TRIM(:email))")
    boolean existsByMail(@Param("email") String email);
    Optional<User> findByEntraOid(String entraOid);

    List<User> findByRole(String role);

    /** The active users of a level, the oldest first: SoQM Admin Team for a notification whose control names none. */
    List<User> findByAccessLevelAndEnabledTrueOrderByIdAsc(AccessLevel level);

    /** Active users of a level (the last active SoQM Team member keeps the role and the account). */
    @Query("SELECT COUNT(u) FROM User u WHERE u.accessLevel = :level AND u.enabled = true")
    long countActiveByAccessLevel(@Param("level") AccessLevel level);

    /** The users whose address has this part before "@" (lower case), on any domain. */
    default List<User> findByMailLocalPart(String localPart) {
        String escaped = localPart.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return findByMailLowerLike(escaped + "@%");
    }

    @Query("SELECT u FROM User u WHERE LOWER(u.mail) LIKE :pattern ESCAPE '!'")
    List<User> findByMailLowerLike(@Param("pattern") String pattern);

    /** The users of these addresses (lower-case), in one query: the names of a list's people. */
    @Query("SELECT u FROM User u WHERE LOWER(TRIM(u.mail)) IN :mails")
    List<User> findByMailLowerIn(@Param("mails") Collection<String> mails);

}
