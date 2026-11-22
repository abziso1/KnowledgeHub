package com.knowledgehub.trash;

import com.knowledgehub.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ContentTrashTest.BlobFixture.class)
@TestPropertySource(properties = "knowledgehub.images.cleanup-enabled=false")
class LibraryTrashTest extends ApiTestSupport {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void restoresLibraryStateAndIndependentContentTrash() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        var other = registerAndLogin(uniqueUsername());
        String library = library(owner, "工作");
        String independent = library(owner, "学习");
        String image = image(owner, library);
        String active = note(owner, library, "active ![图](" + image + ")");
        String deleted = note(owner, library, "originally trashed");
        assertEquals(204, call(owner, "DELETE", deleted, null).status());
        var oldTrash = call(owner, "GET", library + "/content-trash", null).json().path("items");
        assertEquals(1, oldTrash.size());

        assertEquals(404, call(other, "DELETE", library, null).status());
        assertEquals(204, call(owner, "DELETE", library, null).status());
        assertEquals(404, call(owner, "GET", active, null).status());
        assertEquals(404, call(owner, "GET", library + "/search?q=active", null).status());
        mvc.perform(get(image).session(owner.session())).andExpect(status().isNotFound());
        assertFalse(call(owner, "GET", "/api/libraries", null).json().toString().contains(id(library).toString()));
        assertEquals(200, call(owner, "GET", independent + "/notes", null).status());

        String conflicting = library(owner, "工作");
        var restored = call(owner, "POST", trashed(library) + "/restore", null);
        assertEquals(200, restored.status());
        assertEquals("工作（已恢复）", restored.json().path("name").asText());
        assertEquals(200, call(owner, "GET", active, null).status());
        assertEquals(404, call(owner, "GET", deleted, null).status());
        assertEquals(oldTrash, call(owner, "GET", library + "/content-trash", null).json().path("items"));
        assertEquals(200, call(owner, "GET", conflicting + "/notes", null).status());
        mvc.perform(get(image).session(owner.session())).andExpect(status().isOk());
    }

    @Test
    void permanentLibraryDeletionKeepsCleanupJobsAndOtherLibraries() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        String doomed = library(owner, "待清除");
        String surviving = library(owner, "保留");
        String doomedImage = image(owner, doomed);
        String survivingImage = image(owner, surviving);
        String doomedNote = note(owner, doomed, "![图](" + doomedImage + ")");
        assertEquals(204, call(owner, "DELETE", doomedNote, null).status());
        String blobKey = jdbc.queryForObject("select blob_key from images where id = ?", String.class, id(doomedImage));
        assertEquals(404, call(owner, "POST", trashed(doomed) + "/purge", Map.of("confirm", true)).status());
        assertEquals(204, call(owner, "DELETE", doomed, null).status());
        assertEquals(400, call(owner, "POST", trashed(doomed) + "/purge", Map.of("confirm", false)).status());
        assertEquals(204, call(owner, "POST", trashed(doomed) + "/purge", Map.of("confirm", true)).status());
        assertEquals(404, call(owner, "POST", trashed(doomed) + "/restore", null).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from images where id = ?", Integer.class, id(doomedImage)));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from image_cleanup_jobs where blob_key = ?)
                """, Boolean.class, blobKey)));
        mvc.perform(get(survivingImage).session(owner.session())).andExpect(status().isOk());
    }

    @Test
    void ownerCanReachTrashWithoutAnActiveLibrary() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        var libraries = call(owner, "GET", "/api/libraries", null);
        assertEquals(1, libraries.json().size());
        String defaultLibrary = "/api/libraries/" + libraries.json().get(0).path("id").asText();
        assertEquals(204, call(owner, "DELETE", defaultLibrary, null).status());
        assertEquals(0, call(owner, "GET", "/api/libraries", null).json().size());
        var trash = call(owner, "GET", "/api/library-trash", null);
        assertEquals(200, trash.status());
        assertEquals(1, trash.json().path("items").size());
        library(owner, "新库");
    }

    private String library(Actor actor, String name) throws Exception {
        var result = call(actor, "POST", "/api/libraries", Map.of("name", name));
        assertEquals(201, result.status());
        return "/api/libraries/" + result.json().path("id").asText();
    }

    private String image(Actor actor, String library) throws Exception {
        var result = upload(actor, library + "/images", "file", "test.png", "image/png", ContentTrashTest.PNG, Map.of());
        assertEquals(201, result.status());
        return result.json().path("url").asText();
    }

    private String note(Actor actor, String library, String markdown) throws Exception {
        var result = call(actor, "POST", library + "/notes", Map.of("title", "笔记"));
        assertEquals(201, result.status());
        String note = library + "/notes/" + result.json().path("id").asText();
        assertEquals(200, call(actor, "PUT", note, Map.of("version", 0, "title", "笔记", "markdown", markdown)).status());
        return note;
    }

    private String trashed(String library) {
        return "/api/library-trash/" + id(library);
    }

    private UUID id(String path) {
        return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    }
}
