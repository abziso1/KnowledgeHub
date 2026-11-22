package com.knowledgehub.media;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ImageRepository {

    enum State { TEMPORARY, REFERENCED, QUEUED }

    record Image(UUID id, String blobKey, String contentType, State state, Instant expiresAt) {
        boolean availableAt(Instant now) {
            return state == State.REFERENCED || state == State.TEMPORARY && expiresAt.isAfter(now);
        }
    }

    record Expired(UUID accountId, UUID libraryId, UUID imageId) {}

    private final JdbcTemplate jdbc;

    ImageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID libraryId, String key, String contentType, Instant expiresAt) {
        jdbc.update("""
                insert into images (id, library_id, blob_key, content_type, state, expires_at)
                values (?, ?, ?, ?, 'TEMPORARY', ?)
                """, id, libraryId, key, contentType, utc(expiresAt));
    }

    boolean published(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from images where blob_key = ?)",
                Boolean.class, key));
    }

    Optional<Image> find(UUID libraryId, UUID imageId, boolean lock) {
        String query = "select id, blob_key, content_type, state, expires_at from images where library_id = ? and id = ?";
        if (lock) {
            query += " for update";
        }
        return jdbc.query(query, this::map, libraryId, imageId).stream().findFirst();
    }

    Set<UUID> references(UUID libraryId, UUID noteId) {
        return Set.copyOf(jdbc.query("""
                select image_id from note_image_references where library_id = ? and note_id = ?
                """, (row, index) -> row.getObject(1, UUID.class), libraryId, noteId));
    }

    void addReference(UUID libraryId, UUID noteId, UUID imageId) {
        jdbc.update("""
                insert into note_image_references (library_id, note_id, image_id) values (?, ?, ?)
                """, libraryId, noteId, imageId);
        jdbc.update("""
                update images set state = 'REFERENCED'
                where library_id = ? and id = ? and state = 'TEMPORARY'
                """, libraryId, imageId);
    }

    void removeReference(UUID libraryId, UUID noteId, UUID imageId) {
        jdbc.update("""
                delete from note_image_references where library_id = ? and note_id = ? and image_id = ?
                """, libraryId, noteId, imageId);
    }

    List<Expired> expiryCandidates(Instant now) {
        return jdbc.query("""
                select l.owner_id, i.library_id, i.id
                from images i join libraries l on l.id = i.library_id
                where i.state = 'TEMPORARY' and i.expires_at <= ? and l.trashed_at is null
                order by i.expires_at, i.id limit 100
                """, (row, index) -> new Expired(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class)), utc(now));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Image map(ResultSet row, int index) throws SQLException {
        return new Image(row.getObject("id", UUID.class), row.getString("blob_key"),
                row.getString("content_type"), State.valueOf(row.getString("state")),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
