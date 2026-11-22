package com.knowledgehub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest(properties = {
        "knowledgehub.admin.username=kh_test_admin",
        "knowledgehub.admin.password=AdminTest12345",
        "knowledgehub.auth.registration-limit=1000"
})
@AutoConfigureMockMvc
public abstract class ApiTestSupport extends PostgresTestSupport {

    protected static final String TEST_PASSWORD = "UserTest12345";
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    protected record Actor(UUID accountId, MockHttpSession session) {}
    protected record Reply(int status, JsonNode json) {}

    protected Actor actor(String username, String password) throws Exception {
        var session = new MockHttpSession();
        var result = send(request(HttpMethod.POST, "/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(credentials(username, password))), session, true);
        var reply = reply(result);
        assertEquals(200, reply.status(), () -> reply.json().toString());
        return new Actor(UUID.fromString(reply.json().path("id").asText()),
                (MockHttpSession) result.getRequest().getSession(false));
    }

    protected Actor registerAndLogin(String username) throws Exception {
        var reply = call(null, "POST", "/api/auth/register", credentials(username, TEST_PASSWORD));
        assertEquals(201, reply.status(), () -> reply.json().toString());
        return actor(username, TEST_PASSWORD);
    }

    protected Actor loginConfiguredAdmin() throws Exception {
        return actor("kh_test_admin", "AdminTest12345");
    }

    protected Map<String, String> credentials(String username, String password) {
        return Map.of("username", username, "password", password);
    }

    protected String uniqueUsername() {
        return "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    protected Reply call(Actor actor, String method, String path, Object body) throws Exception {
        var verb = HttpMethod.valueOf(method);
        var builder = request(verb, path);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        boolean write = !verb.equals(HttpMethod.GET) && !verb.equals(HttpMethod.HEAD);
        return reply(send(builder, actor == null ? new MockHttpSession() : actor.session(), write));
    }

    protected Reply upload(Actor actor, String path, String field, String filename,
                           String type, byte[] bytes, Map<String, String> fields) throws Exception {
        var builder = multipart(path).file(new MockMultipartFile(field, filename, type, bytes));
        fields.forEach(builder::param);
        return reply(send(builder, actor == null ? new MockHttpSession() : actor.session(), true));
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, MockHttpSession session,
                           boolean csrf) throws Exception {
        if (csrf) {
            var token = reply(mvc.perform(get("/api/auth/csrf").session(session)).andReturn());
            if (token.status() == 200) {
                builder.header(token.json().path("headerName").asText(), token.json().path("token").asText());
            }
        }
        return mvc.perform(builder.session(session)).andReturn();
    }

    private Reply reply(MvcResult result) throws Exception {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        return new Reply(result.getResponse().getStatus(), bytes.length == 0 ? NullNode.instance : json.readTree(bytes));
    }
}
