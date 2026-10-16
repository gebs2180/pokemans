package com.pokemans.app.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findBySubject(String subject);

    /** Creates the user on first login; safe under concurrent first requests. */
    @Modifying
    @Query(value = """
            insert into app_user (id, subject, email, created_at)
            values (:id, :subject, :email, now())
            on conflict (subject) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(UUID id, String subject, String email);
}
