package com.pokemans.app.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * The base of every entity, because we assign ids ourselves. Spring Data can't tell from a set id whether a row
 * is new, so it would merge: an extra SELECT before each insert, and a reused id would quietly become an UPDATE
 * that an append-only table refuses with a confusing error. An entity is new until it is saved or loaded, so
 * saving a new one always inserts and a reused id fails as a duplicate key.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
