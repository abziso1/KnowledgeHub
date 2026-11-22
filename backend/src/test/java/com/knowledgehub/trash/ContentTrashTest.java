package com.knowledgehub.trash;

import com.knowledgehub.ApiTestSupport;
import com.knowledgehub.media.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ContentTrashTest.BlobFixture.class)
@TestPropertySource(properties = "knowledgehub.images.cleanup-enabled=false")
class ContentTrashTest extends ApiTestSupport {

    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aNikAAAAASUVORK5CYII=");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void restoresFolderSubtreeWithNameConflictAndRetainsImages() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor);
        String parent = folder(actor, library, "学习", null);
        String child = folder(actor, library, "数据库", parent);
        String image = image(actor, library);
        String note = note(actor, library, child, "子树含图片 ![图](" + image + ")");
        assertEquals(204, call(actor, "DELETE", library + "/folders/" + parent, null).status());
        assertEquals(404, call(actor, "GET", note, null).status());
        assertEquals(0, call(actor, "GET", library + "/folders", null).json().size());
        var hidden = mvc.perform(get(library + "/search").session(actor.session()).queryParam("q", "子树含图片"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(0, json.readTree(hidden.getContentAsByteArray()).path("items").size());

        folder(actor, library, "学习", null);
        folder(actor, library, "学习（已恢复）", null);
        String batch = batch(actor, library, parent);
        var restored = call(actor, "POST", batch + "/restore", null);
        assertEquals(200, restored.status());
        assertEquals("学习（已恢复）2", restored.json().path("name").asText());
        assertTrue(restored.json().path("parentId").isNull());
        assertEquals(child, call(actor, "GET", note, null).json().path("folderId").asText());
        mvc.perform(get(image).session(actor.session())).andExpect(status().isOk());
    }

    @Test
    void oldBatchSurvivesPermanentRemovalOfItsParent() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor);
        String parent = folder(actor, library, "将永久删除", null);
        String note = note(actor, library, parent, "原笔记");
        String noteId = note.substring(note.lastIndexOf('/') + 1);
        assertEquals(204, call(actor, "DELETE", note, null).status());
        String noteBatch = batch(actor, library, noteId);
        assertEquals(204, call(actor, "DELETE", library + "/folders/" + parent, null).status());
        String folderBatch = batch(actor, library, parent);
        assertEquals(400, call(actor, "POST", folderBatch + "/purge", Map.of("confirm", false)).status());
        assertEquals(204, call(actor, "POST", folderBatch + "/purge", Map.of("confirm", true)).status());
        assertEquals(404, call(actor, "POST", folderBatch + "/restore", null).status());
        var restored = call(actor, "POST", noteBatch + "/restore", null);
        assertEquals(200, restored.status());
        assertTrue(restored.json().path("parentId").isNull());
        assertEquals("原笔记", call(actor, "GET", note, null).json().path("markdown").asText());
    }

    @Test
    void activeAndTrashedReferencesBothKeepImage() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor);
        String image = image(actor, library);
        String first = note(actor, library, null, "![图](" + image + ")");
        String second = note(actor, library, null, "![图](" + image + ")");
        assertEquals(204, call(actor, "DELETE", first, null).status());
        String firstBatch = batch(actor, library, first.substring(first.lastIndexOf('/') + 1));
        purgeNote(actor, library, second);
        assertFalse(cleanupQueued(image));
        assertEquals(200, call(actor, "POST", firstBatch + "/restore", null).status());
        mvc.perform(get(image).session(actor.session())).andExpect(status().isOk());
        purgeNote(actor, library, first);
        assertTrue(cleanupQueued(image));
    }

    private void purgeNote(Actor actor, String library, String note) throws Exception {
        assertEquals(204, call(actor, "DELETE", note, null).status());
        String batch = batch(actor, library, note.substring(note.lastIndexOf('/') + 1));
        assertEquals(204, call(actor, "POST", batch + "/purge", Map.of("confirm", true)).status());
    }

    private String library(Actor actor) throws Exception {
        var result = call(actor, "POST", "/api/libraries", Map.of("name", "内容回收"));
        assertEquals(201, result.status());
        return "/api/libraries/" + result.json().path("id").asText();
    }

    private String folder(Actor actor, String library, String name, String parent) throws Exception {
        var body = parent == null ? Map.of("name", name) : Map.of("name", name, "parentId", parent);
        var result = call(actor, "POST", library + "/folders", body);
        assertEquals(201, result.status());
        return result.json().path("id").asText();
    }

    private String image(Actor actor, String library) throws Exception {
        var result = upload(actor, library + "/images", "file", "test.png", "image/png", PNG, Map.of());
        assertEquals(201, result.status());
        return result.json().path("url").asText();
    }

    private String note(Actor actor, String library, String folder, String markdown) throws Exception {
        var body = folder == null ? Map.of("title", "笔记") : Map.of("title", "笔记", "folderId", folder);
        var result = call(actor, "POST", library + "/notes", body);
        assertEquals(201, result.status());
        String note = library + "/notes/" + result.json().path("id").asText();
        assertEquals(200, call(actor, "PUT", note, Map.of("version", 0, "title", "笔记", "markdown", markdown)).status());
        return note;
    }

    private String batch(Actor actor, String library, String target) throws Exception {
        var result = call(actor, "GET", library + "/content-trash", null);
        assertEquals(200, result.status());
        for (var item : result.json().path("items")) {
            if (item.path("targetId").asText().equals(target)) {
                return library + "/content-trash/" + item.path("id").asText();
            }
        }
        throw new AssertionError("Missing trash batch for " + target);
    }

    private boolean cleanupQueued(String url) {
        UUID id = UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from image_cleanup_jobs j join images i on j.blob_key = i.blob_key where i.id = ?)
                """, Boolean.class, id));
    }

    @TestConfiguration
    static class BlobFixture {
        @Bean @Primary
        BlobStore trashTestBlobStore() {
            return new BlobStore() {
                private final Map<String, byte[]> data = new ConcurrentHashMap<>();

                @Override public String put(byte[] bytes, String extension) {
                    String key = "trash-test/" + UUID.randomUUID() + "." + extension;
                    data.put(key, bytes.clone());
                    return key;
                }

                @Override public InputStream open(String key) throws FileNotFoundException {
                    var bytes = data.get(key);
                    if (bytes == null) {
                        throw new FileNotFoundException(key);
                    }
                    return new ByteArrayInputStream(bytes);
                }

                @Override public void delete(String key) {
                    data.remove(key);
                }
            };
        }
    }
}
