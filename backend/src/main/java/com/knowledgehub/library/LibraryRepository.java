package com.knowledgehub.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
                """, this::map, UUID.randomUUID(), ownerId, name);
    }
    public boolean lockOwner(UUID ownerId) {
        return !jdbc.query("select id from accounts where id = ? and role = 'USER' for update",
                (row, index) -> row.getObject(1, UUID.class), ownerId).isEmpty();
    }

    boolean activeOwned(UUID ownerId, UUID libraryId, boolean lock) {
        String sql = "select id from libraries where id = ? and owner_id = ? and trashed_at is null";
        if (lock) {
            sql += " for update";
        }
        return !jdbc.query(sql, (row, index) -> row.getObject(1, UUID.class), libraryId, ownerId).isEmpty();
    }

    public List<LibraryView> listActive(UUID ownerId) {
        return jdbc.query("""
                select id, name, updated_at from libraries
                where owner_id = ? and trashed_at is null
                order by updated_at desc, id
                """, this::map, ownerId);
    }

    LibraryView rename(UUID libraryId, String name) {
        return jdbc.queryForObject("""
                update libraries set name = ?, updated_at = clock_timestamp()
                where id = ? returning id, name, updated_at
                """, this::map, name, libraryId);
    }

    private LibraryView map(ResultSet row, int index) throws SQLException {
        return new LibraryView(row.getObject("id", UUID.class), row.getString("name"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
