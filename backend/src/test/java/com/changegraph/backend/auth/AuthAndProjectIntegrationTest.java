package com.changegraph.backend.auth;

import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndProjectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private GraphStore graphStore;

    private final JsonMapper json = new JsonMapper();

    @Test
    void registrationLoginProjectsAndCrossUserAccess() throws Exception {
        mockMvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());

        String email = "user-" + UUID.randomUUID() + "@example.com";
        MockHttpSession session = register(email, "ChangeGraph1");
        String storedHash = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertNotEquals("ChangeGraph1", storedHash);
        assertTrue(passwordEncoder.matches("ChangeGraph1", storedHash));

        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"ChangeGraph1\",\"passwordConfirmation\":\"ChangeGraph1\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

        mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());

        MockHttpSession loggedIn = login(email, "ChangeGraph1");
        String projectId = createProject(loggedIn, "Payments");
        mockMvc.perform(get("/api/projects/" + projectId).session(loggedIn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphStatus").value("READY"))
                .andExpect(jsonPath("$.name").value("Payments"));
        mockMvc.perform(patch("/api/projects/" + projectId).with(csrf()).session(loggedIn)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Synthetic payment demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Synthetic payment demo"));

        MockHttpSession other = register("other-" + UUID.randomUUID() + "@example.com", "ChangeGraph1");
        mockMvc.perform(get("/api/projects/" + projectId).session(other)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/" + projectId + "?confirm=true").with(csrf()).session(other))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/projects/" + projectId).with(csrf()).session(loggedIn))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(delete("/api/projects/" + projectId + "?confirm=true").with(csrf()).session(loggedIn))
                .andExpect(status().isNoContent());
    }

    @Test
    void graphInitializationFailureCanBeRetriedWithTheSameName() throws Exception {
        reset(graphStore);
        doThrow(new IllegalStateException("down")).when(graphStore).initialize(org.mockito.ArgumentMatchers.anyString());
        MockHttpSession session = register("retry-" + UUID.randomUUID() + "@example.com", "ChangeGraph1");
        MvcResult created = mockMvc.perform(post("/api/projects").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Retry\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.graphStatus").value("FAILED"))
                .andReturn();
        String projectId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        reset(graphStore);
        mockMvc.perform(post("/api/projects/" + projectId + "/graph").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphStatus").value("READY"));
    }

    private MockHttpSession register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"passwordConfirmation\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String createProject(MockHttpSession session, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }
}
