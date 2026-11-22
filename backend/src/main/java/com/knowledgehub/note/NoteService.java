package com.knowledgehub.note;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.library.FolderRepository;
import com.knowledgehub.library.LibraryAccess;
import com.knowledgehub.library.LibraryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private final NoteRepository notes;
    private final FolderRepository folders;
    private final LibraryAccess access;

    public NoteService(NoteRepository notes, FolderRepository folders, LibraryAccess access) {
        this.notes = notes;
        this.folders = folders;
        this.access = access;
    }

    public record NoteView(UUID id, UUID folderId, String title, String markdown, long version, Instant updatedAt) {}
    public record NoteSummary(UUID id, UUID folderId, String title, long version, Instant updatedAt) {}

    Page<NoteSummary> list(UUID accountId, UUID libraryId, UUID folderId, int page, int requestedSize) {
        access.requireActive(accountId, libraryId);
        folders.requireActive(libraryId, folderId);
        int size = pageSize(page, requestedSize);
        return slice(notes.list(libraryId, folderId, page, size), page, size);
    }

    NoteView get(UUID accountId, UUID libraryId, UUID noteId) {
        access.requireActive(accountId, libraryId);
        return notes.require(libraryId, noteId, false);
    }

    @Transactional
    public NoteView create(UUID accountId, UUID libraryId, UUID folderId, String title) {
        access.lockActive(accountId, libraryId);
        folders.requireActive(libraryId, folderId);
        return notes.insert(libraryId, folderId, LibraryService.requiredName(title), "");
    }

    @Transactional
    public NoteView importMarkdown(UUID accountId, UUID libraryId, UUID folderId, String filename, byte[] bytes) {
        var imported = MarkdownImport.decode(filename, bytes);
        access.lockActive(accountId, libraryId);
        folders.requireActive(libraryId, folderId);
        return notes.insert(libraryId, folderId, imported.title(), imported.markdown());
    }

    @Transactional
    public NoteView save(UUID accountId, UUID libraryId, UUID noteId, long version, String title, String markdown) {
        access.lockActive(accountId, libraryId);
        requireVersion(notes.require(libraryId, noteId, true), version);
        return notes.save(libraryId, noteId, LibraryService.requiredName(title), requireMarkdown(markdown));
    }

    @Transactional
    public NoteView move(UUID accountId, UUID libraryId, UUID noteId, long version, UUID folderId) {
        access.lockActive(accountId, libraryId);
        requireVersion(notes.require(libraryId, noteId, true), version);
        folders.requireActive(libraryId, folderId);
        return notes.move(libraryId, noteId, folderId);
    }

    private void requireVersion(NoteView note, long version) {
        if (note.version() != version) {
            throw new ApiProblem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "笔记已有更新，请重新加载后处理本次修改");
        }
    }

    static String requireMarkdown(String markdown) {
        if (markdown == null || markdown.indexOf(0) >= 0) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Markdown 正文无效");
        }
        return markdown;
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
