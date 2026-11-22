package com.knowledgehub.library;

import com.knowledgehub.auth.CurrentAccount;
import com.knowledgehub.library.LibraryRepository.LibraryView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/libraries")
public class LibraryController {

    private final LibraryService libraries;

    public LibraryController(LibraryService libraries) {
        this.libraries = libraries;
    }

    record NameInput(String name) {}

    @GetMapping
    List<LibraryView> list(@AuthenticationPrincipal CurrentAccount account) {
        return libraries.list(account.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LibraryView create(@AuthenticationPrincipal CurrentAccount account, @RequestBody NameInput input) {
        return libraries.create(account.id(), input.name());
    }

    @PatchMapping("/{libraryId}")
    LibraryView rename(@AuthenticationPrincipal CurrentAccount account, @PathVariable UUID libraryId,
                       @RequestBody NameInput input) {
        return libraries.rename(account.id(), libraryId, input.name());
    }
}
