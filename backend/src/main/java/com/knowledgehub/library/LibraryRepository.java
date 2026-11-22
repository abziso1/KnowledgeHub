package com.knowledgehub.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
    public record TrashedLibrary(UUID id, String name, Instant trashedAt) {}

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

    public void markTrashed(UUID ownerId, UUID libraryId) {
        jdbc.update("update libraries set trashed_at = clock_timestamp() where id = ? and owner_id = ?",
                libraryId, ownerId);
    }

    public List<TrashedLibrary> listTrashed(UUID ownerId, int page, int size) {
        return jdbc.query("""
                select id, name, trashed_at from libraries where owner_id = ? and trashed_at is not null
                order by trashed_at desc, id limit ? offset ?
                """, this::mapTrashed, ownerId, size + 1, (long) page * size);
    }

    public Optional<TrashedLibrary> lockTrashed(UUID ownerId, UUID libraryId) {
        return jdbc.query("""
                select id, name, trashed_at from libraries
                where id = ? and owner_id = ? and trashed_at is not null for update
                """, this::mapTrashed, libraryId, ownerId).stream().findFirst();
    }

    public boolean activeNameInUse(UUID ownerId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from libraries where owner_id = ? and name = ? and trashed_at is null)
                """, Boolean.class, ownerId, name));
    }

    public LibraryView restore(UUID ownerId, UUID libraryId, String name) {
        return jdbc.queryForObject("""
                update libraries set trashed_at = null, name = ?, updated_at = clock_timestamp()
                where owner_id = ? and id = ? returning id, name, updated_at
                """, this::map, name, ownerId, libraryId);
    }

    public void purge(UUID libraryId) {
        jdbc.update("delete from notes where library_id = ?", libraryId);
        jdbc.update("delete from folders where library_id = ?", libraryId);
        jdbc.update("delete from content_trash_batches where library_id = ?", libraryId);
        jdbc.update("delete from images where library_id = ?", libraryId);
        jdbc.update("delete from libraries where id = ?", libraryId);
    }

    private TrashedLibrary mapTrashed(ResultSet row, int index) throws SQLException {
        return new TrashedLibrary(row.getObject("id", UUID.class), row.getString("name"),
                row.getObject("trashed_at", OffsetDateTime.class).toInstant());
    }

    private LibraryView map(ResultSet row, int index) throws SQLException {
        return new LibraryView(row.getObject("id", UUID.class), row.getString("name"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
