package com.knowledgehub.media;

import com.knowledgehub.ApiTestSupport;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ImageLifecycleTest.Fixture.class)
@TestPropertySource(properties = "knowledgehub.images.cleanup-enabled=false")
class ImageLifecycleTest extends ApiTestSupport {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aNikAAAAASUVORK5CYII=");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FakeBlobs blobs;
    @Autowired private TestClock clock;
    @Autowired private MediaService media;
    @Autowired private ImageCleanupWorker worker;

    @AfterEach
    void resetFixture() {
        clock.now.set(TestClock.START);
        blobs.block = null;
        blobs.failPut = false;
        blobs.failDelete = false;
    }

    @Test
    void imageBytesArePrivateAndValidated() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        var other = registerAndLogin(uniqueUsername());
        var admin = loginConfiguredAdmin();
        String library = library(owner, "私有图片");
        assertEquals(413, image(owner, library, "image/png", new byte[10 * 1024 * 1024 + 1]).status());
        assertEquals(400, image(owner, library, "image/jpeg", PNG).status());
        assertEquals(400, image(owner, library, "image/png", new byte[]{1, 2, 3}).status());
        assertEquals(403, image(admin, library, "image/png", PNG).status());
        assertEquals(404, image(other, library, "image/png", PNG).status());
        mvc.perform(multipart(library + "/images")
                .file(new MockMultipartFile("file", "pixel.png", "image/png", PNG)).session(owner.session()))
                .andExpect(status().isForbidden());

        String url = uploadedImage(owner, library);
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).session(admin.session())).andExpect(status().isForbidden());
        mvc.perform(get(url).session(other.session())).andExpect(status().isNotFound());
        mvc.perform(get(url).session(owner.session()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void referencesAreScopedDeduplicatedAndTransactional() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        String library = library(owner, "图片引用");
        String foreignLibrary = library(owner, "其他图片库");
        String local = uploadedImage(owner, library);
        String foreign = uploadedImage(owner, foreignLibrary);
        String note = newNote(owner, library);
        var valid = "![x](" + local + ")\n![again][ref]\n\n[ref]: " + local;
        assertEquals(200, save(owner, note, 0, valid).status());
        assertEquals(1, referenceCount(note));

        var refused = save(owner, note, 1, "![x](" + foreign + ")");
        assertEquals(422, refused.status());
        assertEquals("IMAGE_UNAVAILABLE", refused.json().path("code").asText());
        assertEquals(valid, call(owner, "GET", note, null).json().path("markdown").asText());
        assertEquals(1, referenceCount(note));

        var failedImport = upload(owner, library + "/notes/import", "file", "image.md", "text/markdown",
                ("![x](" + foreign + ")").getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
        assertEquals(422, failedImport.status());
        assertEquals(1, call(owner, "GET", library + "/notes", null).json().path("items").size());

        blobs.failPut = true;
        assertEquals(503, image(owner, library, "image/png", PNG).status());
        assertEquals(200, save(owner, note, 1, "正文不依赖存储").status());
        assertEquals(0, referenceCount(note));
    }

    @Test
    void expiresAtTwentyFourHoursAndRetriesFailedDeletes() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        String library = library(owner, "暂存清理");
        String url = uploadedImage(owner, library);
        String key = key(url);
        String note = newNote(owner, library);
        clock.now.set(TestClock.START.plus(Duration.ofHours(24)).minusSeconds(1));
        mvc.perform(get(url).session(owner.session())).andExpect(status().isOk());
        clock.now.set(TestClock.START.plus(Duration.ofHours(24)));
        assertEquals(422, save(owner, note, 0, "![expired](" + url + ")").status());

        blobs.failDelete = true;
        runCleanup();
        assertTrue(blobs.objects.containsKey(key));
        assertEquals(1, jdbc.queryForObject("select attempts from image_cleanup_jobs where blob_key = ?",
                Integer.class, key));
        assertEquals(422, save(owner, note, 0, "![queued](" + url + ")").status());

        blobs.failDelete = false;
        clock.now.set(clock.instant().plusSeconds(120));
        runCleanup();
        assertFalse(blobs.objects.containsKey(key));
        assertEquals(0, jdbc.queryForObject("select count(*) from image_cleanup_jobs where blob_key = ?",
                Integer.class, key));
    }

    @Test
    void uploadRacingLibraryTrashCannotPublishImage() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        String library = library(owner, "上传竞争");
        var pause = new Pause();
        blobs.block = pause;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = threads.submit(() -> image(owner, library, "image/png", PNG));
            try {
                assertTrue(pause.written.await(10, TimeUnit.SECONDS));
                jdbc.update("update libraries set trashed_at = now() where id = ?", id(library));
            } finally {
                pause.resume.countDown();
            }
            assertEquals(404, pending.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from images where library_id = ?", Integer.class, id(library)));
        assertFalse(blobs.objects.containsKey(pause.key));
    }

    @Test
    void failedImageCommitCompensatesTheBlob() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        String library = library(owner, "提交失败");
        assertEquals(201, image(owner, library, "image/png", PNG).status());
        jdbc.execute("""
                create function test_reject_image_commit() returns trigger language plpgsql as $body$
                begin raise exception 'test commit rejection'; end;
                $body$
                """);
        jdbc.execute("""
                create constraint trigger test_reject_image_commit after insert on images
                deferrable initially deferred for each row execute function test_reject_image_commit()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> media.upload(owner.accountId(), id(library), "image/png", PNG));
            assertFalse(blobs.objects.containsKey(blobs.lastKey));
            assertEquals(1, jdbc.queryForObject("select count(*) from images where library_id = ?", Integer.class, id(library)));
        } finally {
            jdbc.execute("drop trigger test_reject_image_commit on images");
            jdbc.execute("drop function test_reject_image_commit()");
        }
    }

    private void runCleanup() {
        worker.runOnce();
    }

    private String library(Actor actor, String name) throws Exception {
        var reply = call(actor, "POST", "/api/libraries", Map.of("name", name));
        assertEquals(201, reply.status());
        return "/api/libraries/" + reply.json().path("id").asText();
    }

    private String newNote(Actor actor, String library) throws Exception {
        var reply = call(actor, "POST", library + "/notes", Map.of("title", "笔记"));
        assertEquals(201, reply.status());
        return library + "/notes/" + reply.json().path("id").asText();
    }

    private Reply image(Actor actor, String library, String type, byte[] bytes) throws Exception {
        return upload(actor, library + "/images", "file", "pixel.png", type, bytes, Map.of());
    }

    private String uploadedImage(Actor actor, String library) throws Exception {
        var reply = image(actor, library, "application/octet-stream", PNG);
        assertEquals(201, reply.status(), () -> reply.json().toString());
        return reply.json().path("url").asText();
    }

    private Reply save(Actor actor, String note, long version, String markdown) throws Exception {
        return call(actor, "PUT", note, Map.of("version", version, "title", "笔记", "markdown", markdown));
    }

    private int referenceCount(String note) {
        return jdbc.queryForObject("select count(*) from note_image_references where note_id = ?",
                Integer.class, id(note));
    }

    private String key(String image) {
        return jdbc.queryForObject("select blob_key from images where id = ?", String.class, id(image));
    }

    private UUID id(String path) {
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }

    @TestConfiguration
    static class Fixture {
        @Bean @Primary FakeBlobs fakeBlobs() { return new FakeBlobs(); }
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        static final Instant START = Instant.parse("2026-11-22T00:00:00Z");
        final AtomicReference<Instant> now = new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    static class Pause {
        final CountDownLatch written = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        volatile String key;
    }

    static class FakeBlobs implements BlobStore {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        volatile boolean failPut;
        volatile boolean failDelete;
        volatile Pause block;
        volatile String lastKey;

        @Override
        public String put(byte[] data, String extension) throws IOException {
            if (failPut) {
                throw new IOException("temporarily unavailable");
            }
            String key = "test/" + UUID.randomUUID() + "." + extension;
            objects.put(key, data.clone());
            lastKey = key;
            var pause = block;
            if (pause != null) {
                pause.key = key;
                pause.written.countDown();
                try {
                    if (!pause.resume.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("test upload timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            return key;
        }

        @Override
        public InputStream open(String key) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new FileNotFoundException();
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String key) throws IOException {
            if (failDelete) {
                throw new IOException("temporarily unavailable");
            }
            objects.remove(key);
        }
    }
}
