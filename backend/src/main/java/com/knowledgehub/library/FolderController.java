package com.knowledgehub.library;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.library.FolderRepository.FolderView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/libraries/{libraryId}/folders")
public class FolderController {

    private final FolderService folders;

    public FolderController(FolderService folders) {
        this.folders = folders;
    }

    record CreateFolder(UUID parentId, String name) {}
    record MoveFolder(UUID parentId) {}

    @GetMapping
    List<FolderView> list(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId) {
        return folders.list(account.id(), libraryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FolderView create(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                      @RequestBody CreateFolder input) {
        return folders.create(account.id(), libraryId, input.parentId(), input.name());
    }

    @PatchMapping("/{folderId}")
    FolderView rename(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                      @PathVariable UUID folderId, @RequestBody LibraryController.NameInput input) {
        return folders.rename(account.id(), libraryId, folderId, input.name());
    }

    @PutMapping("/{folderId}/parent")
    FolderView move(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                    @PathVariable UUID folderId, @RequestBody MoveFolder input) {
        return folders.move(account.id(), libraryId, folderId, input.parentId());
    }
}
