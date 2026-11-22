package com.knowledgehub.library;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.library.LibraryRepository.LibraryView;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LibraryService {

    private final LibraryRepository libraries;
    private final LibraryAccess access;

    public LibraryService(LibraryRepository libraries, LibraryAccess access) {
        this.libraries = libraries;
        this.access = access;
    }

    List<LibraryView> list(UUID ownerId) {
        return libraries.listActive(ownerId);
    }

    @Transactional
    public LibraryView create(UUID ownerId, String rawName) {
        String name = requiredName(rawName);
        lockOwner(ownerId);
        try {
            return libraries.insert(ownerId, name);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
    }

    @Transactional
    public LibraryView rename(UUID ownerId, UUID libraryId, String rawName) {
        String name = requiredName(rawName);
        lockOwner(ownerId);
        access.lockActive(ownerId, libraryId);
        try {
            return libraries.rename(libraryId, name);
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
    }

    private void lockOwner(UUID ownerId) {
        if (!libraries.lockOwner(ownerId)) {
            throw LibraryAccess.notFound();
        }
    }

    public static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请输入名称");
        }
        String name = value.strip();
        if (name.codePointCount(0, name.length()) > 120 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "名称无效");
        }
        return name;
    }

    static ApiProblem nameConflict() {
        return new ApiProblem(HttpStatus.CONFLICT, "NAME_CONFLICT", "该名称已被使用");
    }
}
