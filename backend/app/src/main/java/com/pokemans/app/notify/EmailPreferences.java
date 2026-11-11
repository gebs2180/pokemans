package com.pokemans.app.notify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Which optional emails a user gets. Only the daily digest is optional; emails about a user's own sales always go. */
@Service
class EmailPreferences {

    private final JdbcClient jdbc;

    EmailPreferences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the user gets the daily new-listings email: yes unless they turned it off. */
    boolean digest(UUID userId) {
        return jdbc.sql("select digest from email_preference where user_id = :user")
                .param("user", userId)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    void setDigest(UUID userId, boolean digest) {
        jdbc.sql("""
                        insert into email_preference (user_id, digest, updated_at) values (:user, :digest, :now)
                        on conflict (user_id) do update set digest = excluded.digest, updated_at = excluded.updated_at
                        """)
                .param("user", userId).param("digest", digest).param("now", Timestamp.from(Instant.now()))
                .update();
    }
}
