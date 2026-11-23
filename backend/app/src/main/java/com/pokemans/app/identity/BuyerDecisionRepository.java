package com.pokemans.app.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BuyerDecisionRepository extends JpaRepository<BuyerDecision, UUID> {
}
