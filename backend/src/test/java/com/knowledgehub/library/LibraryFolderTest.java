package com.knowledgehub.library;

import com.knowledgehub.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryFolderTest extends ApiTestSupport {

    @Test
    void librariesAndFoldersArePrivateAndScoped() throws Exception {
        var owner = registerAndLogin(uniqueUsername());
        var other = registerAndLogin(uniqueUsername());
        var admin = loginConfiguredAdmin();
        String first = library(owner, "学习");
        String second = library(owner, "工作");
        var list = call(owner, "GET", "/api/libraries", null);
        assertEquals(200, list.status());
        assertEquals(3, list.json().size());
        assertEquals(409, call(owner, "POST", "/api/libraries", Map.of("name", " 学习 ")).status());
        assertEquals(404, call(other, "GET", first + "/folders", null).status());
        assertEquals(403, call(admin, "GET", first + "/folders", null).status());

        String parent = folder(owner, first, Map.of("name", "Java"));
        assertEquals(409, call(owner, "POST", first + "/folders", Map.of("name", "Java")).status());
        assertEquals(404, call(owner, "POST", second + "/folders",
                Map.of("name", "Spring", "parentId", parent)).status());
        String child = folder(owner, first, Map.of("name", "Spring", "parentId", parent));
        assertNotEquals(parent, child);
        assertEquals(200, call(owner, "PATCH", first + "/folders/" + child, Map.of("name", "Spring Boot")).status());
        var folders = call(owner, "GET", first + "/folders", null);
        assertEquals(2, folders.json().size());
        assertTrue(folders.json().toString().contains("Spring Boot"));
    }

    @Test
    void concurrentMovesCannotCreateCycle() throws Exception {
        String username = uniqueUsername();
        var firstSession = registerAndLogin(username);
        var secondSession = actor(username, TEST_PASSWORD);
        String library = library(firstSession, "并发目录");
        String a = folder(firstSession, library, Map.of("name", "A"));
        String b = folder(firstSession, library, Map.of("name", "B"));

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = workers.submit(() -> {
                start.await();
                return call(firstSession, "PUT", library + "/folders/" + a + "/parent", Map.of("parentId", b));
            });
            var second = workers.submit(() -> {
                start.await();
                return call(secondSession, "PUT", library + "/folders/" + b + "/parent", Map.of("parentId", a));
            });
            start.countDown();
            var replies = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), replies.stream().map(Reply::status).sorted().toList());
            assertEquals("FOLDER_CYCLE", replies.stream().filter(reply -> reply.status() == 409)
                    .findFirst().orElseThrow().json().path("code").asText());
        }

        var tree = call(firstSession, "GET", library + "/folders", null);
        int roots = 0;
        for (var entry : tree.json()) {
            if (entry.path("parentId").isNull()) {
                roots++;
            }
        }
        assertEquals(1, roots);
    }

    private String library(Actor actor, String name) throws Exception {
        var reply = call(actor, "POST", "/api/libraries", Map.of("name", name));
        assertEquals(201, reply.status(), () -> reply.json().toString());
        return "/api/libraries/" + reply.json().path("id").asText();
    }

    private String folder(Actor actor, String library, Map<String, String> body) throws Exception {
        var reply = call(actor, "POST", library + "/folders", body);
        assertEquals(201, reply.status(), () -> reply.json().toString());
        return reply.json().path("id").asText();
    }
}
