package com.knowledgehub.library;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.library.FolderRepository.FolderView;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolderService {

    private final FolderRepository folders;
    private final LibraryAccess access;

    public FolderService(FolderRepository folders, LibraryAccess access) {
        this.folders = folders;
        this.access = access;
    }

    List<FolderView> list(UUID accountId, UUID libraryId) {
        access.requireActive(accountId, libraryId);
        return folders.list(libraryId);
    }

    @Transactional
    public FolderView create(UUID accountId, UUID libraryId, UUID parentId, String rawName) {
        access.lockActive(accountId, libraryId);
        folders.requireActive(libraryId, parentId);
        String name = LibraryService.requiredName(rawName);
        try {
            return folders.insert(libraryId, parentId, name);
        } catch (DuplicateKeyException exception) {
            throw LibraryService.nameConflict();
        }
    }

    @Transactional
    public FolderView rename(UUID accountId, UUID libraryId, UUID folderId, String rawName) {
        access.lockActive(accountId, libraryId);
        folders.requireActive(libraryId, folderId);
        String name = LibraryService.requiredName(rawName);
        try {
            return folders.rename(libraryId, folderId, name);
        } catch (DuplicateKeyException exception) {
            throw LibraryService.nameConflict();
        }
    }

    @Transactional
    public FolderView move(UUID accountId, UUID libraryId, UUID folderId, UUID parentId) {
        access.lockActive(accountId, libraryId);
        folders.requireActive(libraryId, folderId);
        folders.requireActive(libraryId, parentId);
        if (parentId != null && folders.wouldCreateCycle(libraryId, folderId, parentId)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "FOLDER_CYCLE", "不能将文件夹移入自身或子文件夹");
        }
        try {
            return folders.move(libraryId, folderId, parentId);
        } catch (DuplicateKeyException exception) {
            throw LibraryService.nameConflict();
        }
    }
}
