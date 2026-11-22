package com.knowledgehub.note;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.note.NoteSearch.SearchHit;
import com.knowledgehub.note.NoteService.NoteSummary;
import com.knowledgehub.note.NoteService.NoteView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/libraries/{libraryId}")
public class NoteController {

    private final NoteService notes;
    private final NoteSearch search;

    public NoteController(NoteService notes, NoteSearch search) {
        this.notes = notes;
        this.search = search;
    }

    record CreateNote(UUID folderId, String title) {}
    record SaveNote(@NotNull @PositiveOrZero Long version, String title, String markdown) {}
    record MoveNote(@NotNull @PositiveOrZero Long version, UUID folderId) {}

    @GetMapping("/notes")
    Page<NoteSummary> list(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                           @RequestParam(required = false) UUID folderId, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return notes.list(account.id(), libraryId, folderId, page, size);
    }

    @GetMapping("/notes/{noteId}")
    NoteView get(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                 @PathVariable UUID noteId) {
        return notes.get(account.id(), libraryId, noteId);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    NoteView create(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                    @RequestBody CreateNote input) {
        return notes.create(account.id(), libraryId, input.folderId(), input.title());
    }

    @PostMapping(path = "/notes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    NoteView importMarkdown(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                            @RequestParam(required = false) UUID folderId, @RequestParam MultipartFile file) {
        if (file.getSize() > MarkdownImport.MAX_BYTES) {
            throw MarkdownImport.tooLarge();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "无法读取上传的文件");
        }
        return notes.importMarkdown(account.id(), libraryId, folderId, file.getOriginalFilename(), bytes);
    }

    @PutMapping("/notes/{noteId}")
    NoteView save(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                  @PathVariable UUID noteId, @Valid @RequestBody SaveNote input) {
        return notes.save(account.id(), libraryId, noteId, input.version(), input.title(), input.markdown());
    }

    @PutMapping("/notes/{noteId}/folder")
    NoteView move(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                  @PathVariable UUID noteId, @Valid @RequestBody MoveNote input) {
        return notes.move(account.id(), libraryId, noteId, input.version(), input.folderId());
    }

    @GetMapping("/search")
    Page<SearchHit> search(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                           @RequestParam String q, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return search.search(account.id(), libraryId, q, page, size);
    }
}
