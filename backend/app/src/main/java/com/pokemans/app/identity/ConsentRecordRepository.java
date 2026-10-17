package com.pokemans.app.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    Optional<ConsentRecord> findFirstByOrgIdAndUserIdAndTermsVersionOrderByAcceptedAtDesc(UUID orgId, UUID userId, String termsVersion);
}
