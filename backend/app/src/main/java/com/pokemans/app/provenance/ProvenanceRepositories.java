package com.pokemans.app.provenance;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuthenticityVerificationRepository extends JpaRepository<AuthenticityVerification, UUID> {

    List<AuthenticityVerification> findByCardItemIdInOrderByCreatedAtDesc(Collection<UUID> cardItemIds);
}

interface CustodyRecordRepository extends JpaRepository<CustodyRecord, UUID> {

    List<CustodyRecord> findByCardItemIdInOrderByCreatedAtDesc(Collection<UUID> cardItemIds);
}
