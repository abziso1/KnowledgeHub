package com.knowledgehub.trash;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.common.Page;
import com.knowledgehub.library.LibraryRepository.LibraryView;
import com.knowledgehub.library.LibraryRepository.TrashedLibrary;
import com.knowledgehub.trash.ContentTrashController.Confirmation;
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
@RequestMapping("/api")
public class LibraryTrashController {

    private final LibraryTrashService trash;

    public LibraryTrashController(LibraryTrashService trash) {
        this.trash = trash;
    }

    @DeleteMapping("/libraries/{libraryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trash(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId) {
        trash.trash(account.id(), libraryId);
    }

    @GetMapping("/library-trash")
    Page<TrashedLibrary> list(@AuthenticationPrincipal CurrentAccount account,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        return trash.list(account.id(), page, size);
    }

    @PostMapping("/library-trash/{libraryId}/restore")
    LibraryView restore(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId) {
        return trash.restore(account.id(), libraryId);
    }

    @PostMapping("/library-trash/{libraryId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void purge(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
               @RequestBody Confirmation confirmation) {
        confirmation.require();
        trash.purge(account.id(), libraryId);
    }
}
