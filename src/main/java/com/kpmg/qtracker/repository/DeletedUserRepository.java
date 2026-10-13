package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.DeletedUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DeletedUserRepository extends JpaRepository<DeletedUser, Long> {

    /** The deleted users with one of these addresses (lower-case), in one query. */
    @Query("select d from DeletedUser d where lower(trim(d.mail)) in :mails")
    List<DeletedUser> findByMailLowerIn(@Param("mails") Collection<String> mails);
}
