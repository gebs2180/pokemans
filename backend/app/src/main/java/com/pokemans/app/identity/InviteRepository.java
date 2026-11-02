package com.pokemans.app.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByTokenHash(String tokenHash);

    /** Row-locked, so two people accepting one invite at once can't both join. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invite> findLockedByTokenHash(String tokenHash);

    /** Row-locked, so a revoke and an accept can't both happen. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invite> findLockedByIdAndOrgId(UUID id, UUID orgId);

    List<Invite> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
