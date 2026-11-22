package com.knowledgehub.note;

import com.knowledgehub.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NoteWorkflowTest extends ApiTestSupport {

    @Test
    void importsEditableNotesAndRejectsInvalidFiles() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor, "Markdown 导入");
        String path = library + "/notes/import";
        String markdown = "![截图](./images/demo.png)";
        var imported = upload(actor, path, "file", "学习笔记.md", "text/markdown",
                ("\uFEFF" + markdown).getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals(201, imported.status(), () -> imported.json().toString());
        assertEquals("学习笔记", imported.json().path("title").asText());
        assertEquals(markdown, imported.json().path("markdown").asText());
        assertEquals(0, imported.json().path("version").asLong());
        assertEquals(400, upload(actor, path, "file", "broken.md", "text/markdown",
                new byte[]{(byte) 0xc3, (byte) 0x28}, Map.of()).status());
        assertEquals(413, upload(actor, path, "file", "large.md", "text/markdown",
                new byte[2 * 1024 * 1024 + 1], Map.of()).status());
        var page = call(actor, "GET", library + "/notes", null);
        assertEquals(1, page.json().path("items").size());
    }

    @Test
    void savesAndMovesWithoutOverwritingNewerVersions() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor, "版本");
        String noteId = note(actor, library, "数据库", "旧草稿");
        String path = library + "/notes/" + noteId;
        assertEquals(200, save(actor, path, 1, "数据库", "事务隔离 SerialIZable").status());
        var conflict = save(actor, path, 1, "过期标题", "过期草稿");
        assertEquals(409, conflict.status());
        assertEquals("VERSION_CONFLICT", conflict.json().path("code").asText());
        assertEquals("事务隔离 SerialIZable", call(actor, "GET", path, null).json().path("markdown").asText());

        String commonTitle = "数据库";
        String other = library(actor, "另一知识库");
        note(actor, other, commonTitle, "事务隔离 SERIALIZABLE");
        assertEquals(List.of(noteId), searchIds(actor, library, "事务隔离"));
        assertEquals(List.of(noteId), searchIds(actor, library, "serializable"));
        assertEquals(List.of(noteId), searchIds(actor, library, commonTitle));

        var folder = call(actor, "POST", library + "/folders", Map.of("name", "数据库目录"));
        String folderId = folder.json().path("id").asText();
        var moved = call(actor, "PUT", path + "/folder", Map.of("folderId", folderId, "version", 2));
        assertEquals(200, moved.status());
        assertEquals(3, moved.json().path("version").asInt());
        var results = search(actor, library, "事务隔离");
        assertEquals("数据库目录", results.path("items").get(0).path("folderPath").get(0).asText());
    }

    @Test
    void searchTreatsSqlWildcardsAsLiterals() throws Exception {
        var actor = registerAndLogin(uniqueUsername());
        String library = library(actor, "字面搜索");
        String match = note(actor, library, "第一篇", "A_1 100% !");
        note(actor, library, "第二篇", "AB1 1000");
        assertEquals(List.of(match), searchIds(actor, library, "_"));
        assertEquals(List.of(match), searchIds(actor, library, "%"));
        assertEquals(List.of(match), searchIds(actor, library, "!"));
    }

    private String library(Actor actor, String name) throws Exception {
        var reply = call(actor, "POST", "/api/libraries", Map.of("name", name));
        assertEquals(201, reply.status());
        return "/api/libraries/" + reply.json().path("id").asText();
    }

    private String note(Actor actor, String library, String title, String markdown) throws Exception {
        var created = call(actor, "POST", library + "/notes", Map.of("title", title));
        assertEquals(201, created.status());
        String id = created.json().path("id").asText();
        assertEquals(200, save(actor, library + "/notes/" + id, 0, title, markdown).status());
        return id;
    }

    private Reply save(Actor actor, String path, long version, String title, String markdown) throws Exception {
        return call(actor, "PUT", path, Map.of("version", version, "title", title, "markdown", markdown));
    }

    private List<String> searchIds(Actor actor, String library, String term) throws Exception {
        var reply = search(actor, library, term);
        var ids = new ArrayList<String>();
        reply.path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private JsonNode search(Actor actor, String library, String term) throws Exception {
        var response = mvc.perform(get(library + "/search").session(actor.session()).queryParam("q", term))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }
}
