package com.knowledgehub.trash;

import com.knowledgehub.common.Page;
import com.knowledgehub.library.LibraryAccess;
import com.knowledgehub.library.LibraryRepository;
import com.knowledgehub.library.LibraryRepository.LibraryView;
import com.knowledgehub.library.LibraryRepository.TrashedLibrary;
import com.knowledgehub.media.ImageCleanupQueue;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryTrashService {

    private final LibraryRepository libraries;
    private final LibraryAccess access;
    private final ImageCleanupQueue cleanup;

    public LibraryTrashService(LibraryRepository libraries, LibraryAccess access, ImageCleanupQueue cleanup) {
        this.libraries = libraries;
        this.access = access;
        this.cleanup = cleanup;
    }

    Page<TrashedLibrary> list(UUID accountId, int page, int size) {
        int limit = ContentTrashService.pageSize(page, size);
        return ContentTrashService.slice(libraries.listTrashed(accountId, page, limit), page, limit);
    }

    @Transactional
    public void trash(UUID accountId, UUID libraryId) {
        lockOwner(accountId);
        access.lockActive(accountId, libraryId);
        libraries.markTrashed(accountId, libraryId);
    }

    @Transactional
    public LibraryView restore(UUID accountId, UUID libraryId) {
        lockOwner(accountId);
        var item = libraries.lockTrashed(accountId, libraryId).orElseThrow(LibraryAccess::notFound);
        String name = ContentTrashService.recoveredName(item.name(),
                candidate -> libraries.activeNameInUse(accountId, candidate));
        return libraries.restore(accountId, libraryId, name);
    }

    @Transactional
    public void purge(UUID accountId, UUID libraryId) {
        lockOwner(accountId);
        libraries.lockTrashed(accountId, libraryId).orElseThrow(LibraryAccess::notFound);
        cleanup.enqueueLibrary(libraryId);
        libraries.purge(libraryId);
    }

    private void lockOwner(UUID accountId) {
        if (!libraries.lockOwner(accountId)) {
            throw LibraryAccess.notFound();
        }
    }
}
