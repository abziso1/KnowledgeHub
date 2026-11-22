package com.knowledgehub.library;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LibraryRepository {

    private final JdbcTemplate jdbc;

    public LibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LibraryView(UUID id, String name, Instant updatedAt) {}

    public LibraryView insert(UUID ownerId, String name) {
        return jdbc.queryForObject("""
                insert into libraries (id, owner_id, name)
                values (?, ?, ?)
                returning id, name, updated_at
                """, (row, index) -> new LibraryView(row.getObject("id", UUID.class),
                row.getString("name"), row.getObject("updated_at", OffsetDateTime.class).toInstant()),
                UUID.randomUUID(), ownerId, name);
    }
}
