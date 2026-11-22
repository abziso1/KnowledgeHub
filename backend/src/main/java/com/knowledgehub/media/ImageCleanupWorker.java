package com.knowledgehub.media;

import com.knowledgehub.common.ApiProblem;
import com.knowledgehub.library.LibraryAccess;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
public class ImageCleanupWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageCleanupWorker.class);
    private static final int MAX_JOBS = 32;

    private final ImageRepository images;
    private final ImageCleanupQueue queue;
    private final LibraryAccess access;
    private final BlobStore blobs;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    ImageCleanupWorker(ImageRepository images, ImageCleanupQueue queue, LibraryAccess access, BlobStore blobs,
                       Clock clock, PlatformTransactionManager transactions,
                       @Value("${knowledgehub.images.cleanup-enabled:true}") boolean enabled) {
        this.images = images;
        this.queue = queue;
        this.access = access;
        this.blobs = blobs;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${knowledgehub.images.cleanup-interval:PT1M}",
            initialDelayString = "${knowledgehub.images.cleanup-interval:PT1M}")
    void runScheduled() {
        if (enabled) {
            runOnce();
        }
    }

    public int runOnce() {
        expireTemporaryImages();
        int completed = 0;
        for (int i = 0; i < MAX_JOBS; i++) {
            var claim = queue.claim();
            if (claim.isEmpty()) {
                break;
            }
            var job = claim.get();
            try {
                blobs.delete(job.key());
            } catch (IOException exception) {
                LOGGER.warn("FastDFS cleanup failed for {}", job.key(), exception);
                queue.retry(job);
                continue;
            }
            if (queue.complete(job)) {
                completed++;
            }
        }
        return completed;
    }

    private void expireTemporaryImages() {
        for (var candidate : images.expiryCandidates(clock.instant())) {
            try {
                transaction.executeWithoutResult(status -> {
                    access.lockActive(candidate.accountId(), candidate.libraryId());
                    queue.enqueueUnreferenced(candidate.libraryId(), List.of(candidate.imageId()));
                });
            } catch (ApiProblem problem) {
                if (problem.status() != HttpStatus.NOT_FOUND) {
                    throw problem;
                }
            }
        }
    }
}
