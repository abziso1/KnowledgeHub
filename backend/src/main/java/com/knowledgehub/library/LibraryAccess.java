package com.knowledgehub.library;

import com.knowledgehub.common.ApiProblem;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryAccess {

    private final LibraryRepository libraries;

    public LibraryAccess(LibraryRepository libraries) {
        this.libraries = libraries;
    }

    public void requireActive(UUID accountId, UUID libraryId) {
        requireOwned(accountId, libraryId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActive(UUID accountId, UUID libraryId) {
        requireOwned(accountId, libraryId, true);
    }

    private void requireOwned(UUID accountId, UUID libraryId, boolean lock) {
        if (!libraries.activeOwned(accountId, libraryId, lock)) {
            throw notFound();
        }
    }

    public static ApiProblem notFound() {
        return new ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在");
    }
}
