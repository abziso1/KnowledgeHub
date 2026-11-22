package com.knowledgehub.media;

import com.knowledgehub.common.ApiProblem;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarkdownImageReferences {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private static final Pattern IMAGE_URL = Pattern.compile("/api/libraries/(" + UUID_PATTERN + ")/images/("
            + UUID_PATTERN + ")");
    private static final Parser MARKDOWN = Parser.builder().build();

    private final ImageRepository images;
    private final ImageCleanupQueue cleanup;
    private final Clock clock;

    MarkdownImageReferences(ImageRepository images, ImageCleanupQueue cleanup, Clock clock) {
        this.images = images;
        this.cleanup = cleanup;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID libraryId, UUID noteId, String markdown) {
        var desired = targets(libraryId, markdown);
        var previous = images.references(libraryId, noteId);
        var now = clock.instant();
        for (UUID id : desired) {
            var image = images.find(libraryId, id, true).orElseThrow(MarkdownImageReferences::unavailable);
            if (!image.availableAt(now)) {
                throw unavailable();
            }
            if (!previous.contains(id)) {
                images.addReference(libraryId, noteId, id);
            }
        }

        var removed = new HashSet<>(previous);
        removed.removeAll(desired);
        removed.forEach(id -> images.removeReference(libraryId, noteId, id));
        cleanup.enqueueUnreferenced(libraryId, removed);
    }

    private Set<UUID> targets(UUID libraryId, String markdown) {
        Set<UUID> ids = new HashSet<>();
        MARKDOWN.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                var match = IMAGE_URL.matcher(image.getDestination());
                if (match.matches()) {
                    if (!libraryId.equals(UUID.fromString(match.group(1)))) {
                        throw unavailable();
                    }
                    ids.add(UUID.fromString(match.group(2)));
                }
                super.visit(image);
            }
        });
        return ids;
    }

    private static ApiProblem unavailable() {
        return new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "IMAGE_UNAVAILABLE", "图片不可用，请重新上传或更换引用");
    }
}
