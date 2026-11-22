package com.knowledgehub.trash;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.common.Page;
import com.knowledgehub.trash.ContentTrashService.ContentTrashItem;
import com.knowledgehub.trash.ContentTrashService.RestoredContent;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/libraries/{libraryId}")
public class ContentTrashController {

    private final ContentTrashService trash;

    public ContentTrashController(ContentTrashService trash) {
        this.trash = trash;
    }

    record Confirmation(Boolean confirm) {
        void require() {
            if (!Boolean.TRUE.equals(confirm)) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请确认永久删除");
            }
        }
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trashNote(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                   @PathVariable UUID noteId) {
        trash.trashNote(account.id(), libraryId, noteId);
    }

    @DeleteMapping("/folders/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trashFolder(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                     @PathVariable UUID folderId) {
        trash.trashFolder(account.id(), libraryId, folderId);
    }

    @GetMapping("/content-trash")
    Page<ContentTrashItem> list(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return trash.list(account.id(), libraryId, page, size);
    }

    @PostMapping("/content-trash/{batchId}/restore")
    RestoredContent restore(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                            @PathVariable UUID batchId) {
        return trash.restore(account.id(), libraryId, batchId);
    }

    @PostMapping("/content-trash/{batchId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void purge(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
               @PathVariable UUID batchId, @RequestBody Confirmation confirmation) {
        confirmation.require();
        trash.purge(account.id(), libraryId, batchId);
    }
}
