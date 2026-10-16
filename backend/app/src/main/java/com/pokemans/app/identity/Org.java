package com.pokemans.app.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A partner (shop, group-submission operator) or a collector's own account. */
@Entity
@Table(name = "org")
public class Org {

    @Id
    private UUID id;
    private String name;
    @Enumerated(EnumType.STRING)
    private OrgType type;
    private Instant createdAt;

    protected Org() {
    }

    public Org(String name, OrgType type) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public OrgType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
