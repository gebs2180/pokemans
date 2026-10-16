package com.pokemans.app.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrgRepository extends JpaRepository<Org, UUID> {

    List<Org> findAllByOrderByNameAsc();
}
