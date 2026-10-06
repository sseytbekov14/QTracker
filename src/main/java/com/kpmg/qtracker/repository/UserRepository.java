package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Addresses match ignoring case and surrounding spaces: people type them in any case,
    // and assignment lists may keep a spelling that differs from the stored user
    @Query("SELECT u FROM User u WHERE LOWER(u.mail) = LOWER(TRIM(:mail))")
    Optional<User> findByMail(@Param("mail") String mail);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u "
            + "WHERE LOWER(u.mail) = LOWER(TRIM(:email))")
    boolean existsByMail(@Param("email") String email);
    Optional<User> findByEntraOid(String entraOid);

    List<User> findByRole(String role);

}
