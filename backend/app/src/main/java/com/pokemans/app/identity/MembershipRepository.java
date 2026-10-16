package com.pokemans.app.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserId(UUID userId);

    List<Membership> findByOrgId(UUID orgId);

    Optional<Membership> findByOrgIdAndUserId(UUID orgId, UUID userId);
}
