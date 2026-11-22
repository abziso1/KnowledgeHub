package com.knowledgehub.media;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.library.LibraryAccess;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MediaService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaService.class);
    private static final Duration TEMPORARY_LIFETIME = Duration.ofHours(24);

    private final BlobStore blobs;
    private final LibraryAccess access;
    private final ImageRepository images;
    private final ImageCleanupQueue cleanup;
    private final Clock clock;
    private final TransactionTemplate commit;

    MediaService(BlobStore blobs, LibraryAccess access, ImageRepository images, ImageCleanupQueue cleanup,
                 Clock clock, PlatformTransactionManager transactions) {
        this.blobs = blobs;
        this.access = access;
        this.images = images;
        this.cleanup = cleanup;
        this.clock = clock;
        this.commit = new TransactionTemplate(transactions);
        this.commit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record ImageView(UUID id, String url) {}
    public record ImageContent(String contentType, InputStream body) {}

    public ImageView upload(UUID accountId, UUID libraryId, String declaredContentType, byte[] bytes) {
        access.requireActive(accountId, libraryId);
        var format = ImageFormat.identify(bytes, declaredContentType);
        UUID id = UUID.randomUUID();
        String key;
        try {
            key = blobs.put(bytes, format.extension());
        } catch (IOException exception) {
            LOGGER.warn("Image upload failed before FastDFS returned a key, image request {}", id, exception);
            throw storageUnavailable();
        }

        try {
            commit.executeWithoutResult(status -> {
                access.lockActive(accountId, libraryId);
                images.insert(id, libraryId, key, format.contentType(), clock.instant().plus(TEMPORARY_LIFETIME));
            });
        } catch (RuntimeException exception) {
            compensate(id, key);
            throw exception;
        }
        return new ImageView(id, "/api/libraries/" + libraryId + "/images/" + id);
    }

    public ImageContent open(UUID accountId, UUID libraryId, UUID imageId) {
        access.requireActive(accountId, libraryId);
        var image = images.find(libraryId, imageId, false).filter(item -> item.availableAt(clock.instant()))
                .orElseThrow(LibraryAccess::notFound);
        try {
            return new ImageContent(image.contentType(), blobs.open(image.blobKey()));
        } catch (FileNotFoundException exception) {
            throw LibraryAccess.notFound();
        } catch (IOException exception) {
            LOGGER.warn("Image read failed, image {}", imageId, exception);
            throw storageUnavailable();
        }
    }

    private void compensate(UUID imageId, String key) {
        try {
            if (images.published(key)) {
                return;
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Unable to verify image commit, image {}, FastDFS key {}", imageId, key, exception);
            return;
        }

        try {
            blobs.delete(key);
        } catch (IOException exception) {
            LOGGER.warn("Image compensation needs retry, image {}, FastDFS key {}", imageId, key, exception);
            try {
                cleanup.enqueueUnpublished(key);
            } catch (RuntimeException queueError) {
                LOGGER.error("Unable to queue image compensation, FastDFS key {}", key, queueError);
            }
        }
    }

    private ApiProblem storageUnavailable() {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "图片存储暂时不可用");
    }
}
