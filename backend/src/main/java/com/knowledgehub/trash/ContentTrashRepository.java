package com.knowledgehub.trash;

import com.knowledgehub.library.LibraryAccess;
import com.knowledgehub.trash.ContentTrashService.ContentTrashItem;
import com.knowledgehub.trash.ContentTrashService.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContentTrashRepository {

    private final JdbcTemplate jdbc;

    ContentTrashRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID createBatch(UUID libraryId, UUID targetId, Kind kind) {
        UUID id = UUID.randomUUID();
        String sql = """
                insert into content_trash_batches (id, library_id, kind, target_id, name)
                select ?, library_id, ?, id, %s from %s
                where library_id = ? and id = ? and trash_batch_id is null
                """.formatted(kind.nameColumn(), kind.table());
        if (jdbc.update(sql, id, kind.name(), libraryId, targetId) == 0) {
            throw LibraryAccess.notFound();
        }
        return id;
    }

    void trashNote(UUID libraryId, UUID noteId, UUID batchId) {
        jdbc.update("update notes set trash_batch_id = ? where library_id = ? and id = ?", batchId, libraryId, noteId);
    }

    void trashSubtree(UUID libraryId, UUID folderId, UUID batchId) {
        jdbc.update("""
                with recursive subtree(id) as (
                    select id from folders where library_id = ? and id = ? and trash_batch_id is null
                    union all
                    select f.id from folders f join subtree s on f.parent_id = s.id
                    where f.library_id = ? and f.trash_batch_id is null
                ), marked as (
                    update folders set trash_batch_id = ? where id in (select id from subtree) returning id
                )
                update notes set trash_batch_id = ?
                where library_id = ? and trash_batch_id is null and folder_id in (select id from marked)
                """, libraryId, folderId, libraryId, batchId, batchId, libraryId);
    }

    List<ContentTrashItem> list(UUID libraryId, int page, int size) {
        return jdbc.query("""
                select id, kind, target_id, name, trashed_at from content_trash_batches
                where library_id = ? order by trashed_at desc, id limit ? offset ?
                """, this::map, libraryId, size + 1, (long) page * size);
    }

    ContentTrashItem find(UUID libraryId, UUID batchId) {
        return jdbc.query("""
                select id, kind, target_id, name, trashed_at from content_trash_batches where library_id = ? and id = ?
                """, this::map, libraryId, batchId).stream().findFirst().orElseThrow(LibraryAccess::notFound);
    }

    UUID activeOriginalParent(UUID libraryId, ContentTrashItem item) {
        var kind = item.kind();
        String sql = """
                select p.id
                from %s t left join folders p
                    on p.library_id = t.library_id and p.id = t.%s and p.trash_batch_id is null
                where t.library_id = ? and t.id = ? and t.trash_batch_id = ?
                """.formatted(kind.table(), kind.parentColumn());
        return jdbc.queryForObject(sql, UUID.class, libraryId, item.targetId(), item.id());
    }

    boolean folderNameInUse(UUID libraryId, UUID parentId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from folders
                    where library_id = ? and parent_id is not distinct from cast(? as uuid)
                        and name = ? and trash_batch_id is null)
                """, Boolean.class, libraryId, parentId, name));
    }

    void restore(UUID libraryId, ContentTrashItem item, UUID parentId, String name) {
        var kind = item.kind();
        jdbc.update("update %s set %s = ?, %s = ? where library_id = ? and id = ? and trash_batch_id = ?"
                .formatted(kind.table(), kind.parentColumn(), kind.nameColumn()),
                parentId, name, libraryId, item.targetId(), item.id());
        jdbc.update("update folders set trash_batch_id = null where library_id = ? and trash_batch_id = ?",
                libraryId, item.id());
        jdbc.update("update notes set trash_batch_id = null where library_id = ? and trash_batch_id = ?",
                libraryId, item.id());
        removeBatch(libraryId, item.id());
    }

    List<UUID> purge(UUID libraryId, UUID batchId) {
        var imageIds = jdbc.query("""
                select distinct r.image_id from notes n join note_image_references r
                    on r.library_id = n.library_id and r.note_id = n.id
                where n.library_id = ? and n.trash_batch_id = ?
                """, (row, index) -> row.getObject(1, UUID.class), libraryId, batchId);

        detachOtherBatches(libraryId, batchId, Kind.NOTE);
        detachOtherBatches(libraryId, batchId, Kind.FOLDER);
        jdbc.update("delete from notes where library_id = ? and trash_batch_id = ?", libraryId, batchId);
        jdbc.update("delete from folders where library_id = ? and trash_batch_id = ?", libraryId, batchId);
        removeBatch(libraryId, batchId);
        return imageIds;
    }

    private void detachOtherBatches(UUID libraryId, UUID batchId, Kind kind) {
        String sql = """
                update %s set %s = null
                where library_id = ? and trash_batch_id is distinct from ?
                    and %s in (select id from folders where library_id = ? and trash_batch_id = ?)
                """.formatted(kind.table(), kind.parentColumn(), kind.parentColumn());
        jdbc.update(sql, libraryId, batchId, libraryId, batchId);
    }

    private void removeBatch(UUID libraryId, UUID batchId) {
        jdbc.update("delete from content_trash_batches where library_id = ? and id = ?", libraryId, batchId);
    }

    private ContentTrashItem map(ResultSet row, int index) throws SQLException {
        return new ContentTrashItem(row.getObject("id", UUID.class), Kind.valueOf(row.getString("kind")),
                row.getObject("target_id", UUID.class), row.getString("name"),
                row.getObject("trashed_at", OffsetDateTime.class).toInstant());
    }
}
