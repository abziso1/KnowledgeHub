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
public class FolderRepository {

    private final JdbcTemplate jdbc;

    public FolderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FolderView(UUID id, UUID parentId, String name, Instant updatedAt) {}

    public void requireActive(UUID libraryId, UUID folderId) {
        if (folderId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from folders where library_id = ? and id = ? and trash_batch_id is null)
                """, Boolean.class, libraryId, folderId))) {
            throw LibraryAccess.notFound();
        }
    }

    List<FolderView> list(UUID libraryId) {
        return jdbc.query("""
                select id, parent_id, name, updated_at
                from folders where library_id = ? and trash_batch_id is null
                order by name, id
                """, this::map, libraryId);
    }

    FolderView insert(UUID libraryId, UUID parentId, String name) {
        return jdbc.queryForObject("""
                insert into folders (id, library_id, parent_id, name)
                values (?, ?, ?, ?) returning id, parent_id, name, updated_at
                """, this::map, UUID.randomUUID(), libraryId, parentId, name);
    }

    FolderView rename(UUID libraryId, UUID id, String name) {
        return jdbc.queryForObject("""
                update folders set name = ?, updated_at = clock_timestamp()
                where library_id = ? and id = ?
                returning id, parent_id, name, updated_at
                """, this::map, name, libraryId, id);
    }

    FolderView move(UUID libraryId, UUID id, UUID parentId) {
        return jdbc.queryForObject("""
                update folders set parent_id = ?, updated_at = clock_timestamp()
                where library_id = ? and id = ?
                returning id, parent_id, name, updated_at
                """, this::map, parentId, libraryId, id);
    }

    boolean wouldCreateCycle(UUID libraryId, UUID id, UUID parentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                with recursive ancestors(id, parent_id) as (
                    select id, parent_id from folders where library_id = ? and id = ?
                    union
                    select f.id, f.parent_id from folders f join ancestors a on f.id = a.parent_id
                    where f.library_id = ?
                )
                select exists(select 1 from ancestors where id = ?)
                """, Boolean.class, libraryId, parentId, libraryId, id));
    }

    private FolderView map(ResultSet row, int index) throws SQLException {
        return new FolderView(row.getObject("id", UUID.class), row.getObject("parent_id", UUID.class),
                row.getString("name"), row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
