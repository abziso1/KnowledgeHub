package com.knowledgehub.note;

import com.knowledgehub.library.LibraryAccess;
import com.knowledgehub.note.NoteService.NoteSummary;
import com.knowledgehub.note.NoteService.NoteView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NoteRepository {

    private final JdbcTemplate jdbc;

    public NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    NoteView require(UUID libraryId, UUID noteId, boolean lock) {
        String query = """
                select id, folder_id, title, markdown, version, updated_at from notes
                where library_id = ? and id = ? and trash_batch_id is null
                """;
        if (lock) {
            query += " for update";
        }
        return jdbc.query(query, this::view, libraryId, noteId).stream().findFirst()
                .orElseThrow(LibraryAccess::notFound);
    }

    List<NoteSummary> list(UUID libraryId, UUID folderId, int page, int size) {
        return jdbc.query("""
                select id, folder_id, title, version, updated_at from notes
                where library_id = ? and folder_id is not distinct from cast(? as uuid) and trash_batch_id is null
                order by updated_at desc, id limit ? offset ?
                """, this::summary, libraryId, folderId, size + 1, (long) page * size);
    }

    NoteView insert(UUID libraryId, UUID folderId, String title, String markdown) {
        return jdbc.queryForObject("""
                insert into notes (id, library_id, folder_id, title, markdown)
                values (?, ?, ?, ?, ?)
                returning id, folder_id, title, markdown, version, updated_at
                """, this::view, UUID.randomUUID(), libraryId, folderId, title, markdown);
    }

    NoteView save(UUID libraryId, UUID id, String title, String markdown) {
        return jdbc.queryForObject("""
                update notes set title = ?, markdown = ?, version = version + 1, updated_at = clock_timestamp()
                where library_id = ? and id = ?
                returning id, folder_id, title, markdown, version, updated_at
                """, this::view, title, markdown, libraryId, id);
    }

    NoteView move(UUID libraryId, UUID id, UUID folderId) {
        return jdbc.queryForObject("""
                update notes set folder_id = ?, version = version + 1, updated_at = clock_timestamp()
                where library_id = ? and id = ?
                returning id, folder_id, title, markdown, version, updated_at
                """, this::view, folderId, libraryId, id);
    }

    private NoteSummary summary(ResultSet row, int index) throws SQLException {
        return new NoteSummary(row.getObject("id", UUID.class), row.getObject("folder_id", UUID.class),
                row.getString("title"), row.getLong("version"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private NoteView view(ResultSet row, int index) throws SQLException {
        var item = summary(row, index);
        return new NoteView(item.id(), item.folderId(), item.title(), row.getString("markdown"),
                item.version(), item.updatedAt());
    }
}
