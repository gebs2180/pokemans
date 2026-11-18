package com.pokemans.app.condition;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DefectMarkRepository extends JpaRepository<DefectMark, UUID> {

    /** The marks still standing on a card, oldest first. Removed ones stay in the table. */
    List<DefectMark> findByCardItemIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID cardItemId);
}
