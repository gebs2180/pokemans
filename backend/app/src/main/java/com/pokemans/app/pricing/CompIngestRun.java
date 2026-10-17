package com.pokemans.app.pricing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One ingestion pass over the tracked cards for one price source. */
@Entity
@Table(name = "comp_ingest_run")
class CompIngestRun {

    @Id
    private UUID id;
    private String source;
    private LocalDate fromDate;
    private LocalDate toDate;
    private int cards;
    private int fetched;
    private int inserted;
    private int duplicates;
    private int rejected;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> errors;
    private Instant startedAt;
    private Instant finishedAt;

    protected CompIngestRun() {
    }

    CompIngestRun(String source, LocalDate fromDate, LocalDate toDate, int cards) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.cards = cards;
        this.errors = new ArrayList<>();
        this.startedAt = Instant.now();
    }

    void fetched(int count) {
        fetched += count;
    }

    void inserted() {
        inserted++;
    }

    void duplicate() {
        duplicates++;
    }

    void rejected(String reason) {
        rejected++;
        errors.add(reason);
    }

    void failed(String reason) {
        errors.add(reason);
    }

    void finish() {
        finishedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    String getSource() {
        return source;
    }

    int getFetched() {
        return fetched;
    }

    int getInserted() {
        return inserted;
    }

    int getDuplicates() {
        return duplicates;
    }

    int getRejected() {
        return rejected;
    }

    List<String> getErrors() {
        return List.copyOf(errors);
    }
}
