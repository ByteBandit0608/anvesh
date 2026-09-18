package dev.bhavya.anvesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: real Postgres + pgvector in Docker (Testcontainers), hash embedder.
 * Skipped automatically when Docker isn't available (e.g. some CI runners / this sandbox).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "anvesh.embedding.provider=hash")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnveshIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @Order(1)
    void ingestThenSearchRoundTrip() throws Exception {
        String body = """
            {"title":"Drift notes","language":"en","metadata":{"topic":"ml"},
             "body":"Data drift happens when the statistical properties of input features change over time. Monitoring with Evidently AI detects drift before model accuracy collapses."}
            """;
        MvcResult res = mvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String id = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

        awaitIndexed(id);

        // Duplicate submission is idempotent.
        mvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.id").value(id));

        for (String mode : new String[]{"hybrid", "vector", "keyword"}) {
            mvc.perform(get("/api/v1/search").param("q", "drift monitoring").param("mode", mode))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value(mode))
                    .andExpect(jsonPath("$.hits[0].documentId").value(id));
        }

        mvc.perform(delete("/api/v1/documents/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void validationErrorsAreStructured() throws Exception {
        mvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.title").exists())
                .andExpect(jsonPath("$.details.body").exists());
    }

    @Test
    void badModeIs400() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "x").param("mode", "magic"))
                .andExpect(status().isBadRequest());
    }

    private void awaitIndexed(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            MvcResult r = mvc.perform(get("/api/v1/documents/" + id)).andReturn();
            JsonNode node = json.readTree(r.getResponse().getContentAsString());
            String status = node.get("status").asText();
            if (status.equals("INDEXED")) return;
            assertThat(status).as("indexing error: " + node.get("error")).isNotEqualTo("FAILED");
            Thread.sleep(100);
        }
        throw new AssertionError("document " + id + " never reached INDEXED");
    }
}
