package com.pokemans.app.identity;

import com.pokemans.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A person who has logged in at least once, keyed by the OIDC subject. */
@Entity
@Table(name = "app_user")
public class AppUser extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private String subject;
    private String email;
    private Instant createdAt;

    protected AppUser() {
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
