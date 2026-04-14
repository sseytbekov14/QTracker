package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByMail(String mail);
    boolean existsByMail(String email);
    Optional<User> findByEntraOid(String entraOid);

    List<User> findByRole(String role);

    List<User> findByRoleIgnoreCaseOrSecondaryRoleIgnoreCase(String role, String secondaryRole);
}
