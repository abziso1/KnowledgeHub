package com.knowledgehub.trash;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.library.LibraryAccess;
import com.knowledgehub.media.ImageCleanupQueue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentTrashService {

    enum Kind {
        NOTE("notes", "title", "folder_id"),
        FOLDER("folders", "name", "parent_id");

        private final String table;
        private final String nameColumn;
        private final String parentColumn;

        Kind(String table, String nameColumn, String parentColumn) {
            this.table = table;
            this.nameColumn = nameColumn;
            this.parentColumn = parentColumn;
        }

        String table() { return table; }
        String nameColumn() { return nameColumn; }
        String parentColumn() { return parentColumn; }
    }

    public record ContentTrashItem(UUID id, Kind kind, UUID targetId, String name, Instant trashedAt) {}
    public record RestoredContent(UUID id, Kind kind, UUID parentId, String name) {}

    private final LibraryAccess access;
    private final ContentTrashRepository trash;
    private final ImageCleanupQueue cleanup;

    ContentTrashService(LibraryAccess access, ContentTrashRepository trash, ImageCleanupQueue cleanup) {
        this.access = access;
        this.trash = trash;
        this.cleanup = cleanup;
    }

    Page<ContentTrashItem> list(UUID accountId, UUID libraryId, int page, int size) {
        access.requireActive(accountId, libraryId);
        int limit = pageSize(page, size);
        return slice(trash.list(libraryId, page, limit), page, limit);
    }

    @Transactional
    public void trashNote(UUID accountId, UUID libraryId, UUID noteId) {
        access.lockActive(accountId, libraryId);
        UUID batchId = trash.createBatch(libraryId, noteId, Kind.NOTE);
        trash.trashNote(libraryId, noteId, batchId);
    }

    @Transactional
    public void trashFolder(UUID accountId, UUID libraryId, UUID folderId) {
        access.lockActive(accountId, libraryId);
        UUID batchId = trash.createBatch(libraryId, folderId, Kind.FOLDER);
        trash.trashSubtree(libraryId, folderId, batchId);
    }

    @Transactional
    public RestoredContent restore(UUID accountId, UUID libraryId, UUID batchId) {
        access.lockActive(accountId, libraryId);
        var item = trash.find(libraryId, batchId);
        UUID parent = trash.activeOriginalParent(libraryId, item);
        String name = item.kind() == Kind.FOLDER
                ? recoveredName(item.name(), candidate -> trash.folderNameInUse(libraryId, parent, candidate))
                : item.name();
        trash.restore(libraryId, item, parent, name);
        return new RestoredContent(item.targetId(), item.kind(), parent, name);
    }

    @Transactional
    public void purge(UUID accountId, UUID libraryId, UUID batchId) {
        access.lockActive(accountId, libraryId);
        trash.find(libraryId, batchId);
        cleanup.enqueueUnreferenced(libraryId, trash.purge(libraryId, batchId));
    }

    static String recoveredName(String original, Predicate<String> exists) {
        if (!exists.test(original)) {
            return original;
        }
        for (int attempt = 1; ; attempt++) {
            String suffix = "（已恢复）" + (attempt == 1 ? "" : attempt);
            int prefixLength = Math.min(original.codePointCount(0, original.length()), 120 - suffix.length());
            String candidate = original.substring(0, original.offsetByCodePoints(0, prefixLength)) + suffix;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
    }

    static int pageSize(int page, int size) {
        if (page < 0 || size < 1) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "分页参数无效");
        }
        return Math.min(size, 100);
    }

    static <T> Page<T> slice(List<T> rows, int page, int size) {
        return new Page<>(rows.subList(0, Math.min(rows.size(), size)), page, size, rows.size() > size);
    }
}
