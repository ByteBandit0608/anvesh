package dev.bhavya.anvesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bhavya.anvesh.document.DocumentRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
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
    @Autowired JdbcTemplate jdbc;
    // WHY @SpyBean: a real repository against real Postgres, but with the ability to make ONE method
    // throw on demand. That's how we simulate "connection dropped between insertChunks and markIndexed".
    @SpyBean DocumentRepository documentsSpy;

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

        // Regression: natural-language queries with stop words must still hit in keyword mode.
        // (With AND semantics + 'simple' config this returned 0 hits and hybrid silently became vector-only.)
        mvc.perform(get("/api/v1/search").param("q", "why does model accuracy drop over time").param("mode", "keyword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.hits[0].documentId").value(id));

        // tsquery syntax in user input must not cause a 500.
        mvc.perform(get("/api/v1/search").param("q", "drift & (monitoring | !x):*").param("mode", "keyword"))
                .andExpect(status().isOk());

        for (String mode : new String[]{"hybrid", "vector", "keyword"}) {
            mvc.perform(get("/api/v1/search").param("q", "drift monitoring").param("mode", mode))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value(mode))
                    .andExpect(jsonPath("$.hits[0].documentId").value(id));

            // Filters: matching ones keep the hit, non-matching ones remove it — in every mode.
            mvc.perform(get("/api/v1/search").param("q", "drift monitoring").param("mode", mode)
                            .param("lang", "en").param("filter", "topic:ml"))
                    .andExpect(jsonPath("$.hits[0].documentId").value(id));
            mvc.perform(get("/api/v1/search").param("q", "drift monitoring").param("mode", mode).param("lang", "te"))
                    .andExpect(jsonPath("$.count").value(0));
            mvc.perform(get("/api/v1/search").param("q", "drift monitoring").param("mode", mode).param("filter", "topic:energy"))
                    .andExpect(jsonPath("$.count").value(0));
        }
        mvc.perform(get("/api/v1/search").param("q", "drift").param("filter", "notakeyvalue"))
                .andExpect(status().isBadRequest());
        // Per-request fusion overrides are accepted.
        mvc.perform(get("/api/v1/search").param("q", "drift").param("rrfK", "10").param("candidateMultiplier", "2"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/documents/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/documents/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void failedIndexLeavesNoOrphanChunks_andReindexRecovers() throws Exception {
        String body = """
            {"title":"Rollback probe","language":"en",
             "body":"Offset pagination gets slow on deep pages because the database still scans skipped rows. Keyset pagination fixes this."}
            """;
        // Arm the failure: markIndexed blows up AFTER insertChunks has run inside the same transaction.
        doThrow(new RuntimeException("simulated connection loss")).when(documentsSpy).markIndexed(any());

        MvcResult res = mvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        UUID id = UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());

        awaitStatus(id.toString(), "FAILED");
        Integer chunks = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id);
        assertThat(chunks).as("chunks must have been rolled back with the failed status flip").isZero();
        mvc.perform(get("/api/v1/documents/" + id))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("simulated connection loss")));

        // Disarm and retry through the public API.
        doCallRealMethod().when(documentsSpy).markIndexed(any());
        mvc.perform(post("/api/v1/documents/" + id + "/reindex")).andExpect(status().isAccepted());
        awaitStatus(id.toString(), "INDEXED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isEqualTo(1);

        // Reindexing again is idempotent: still exactly one chunk, not two.
        mvc.perform(post("/api/v1/documents/" + id + "/reindex")).andExpect(status().isAccepted());
        awaitStatus(id.toString(), "INDEXED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isEqualTo(1);

        mvc.perform(post("/api/v1/documents/" + UUID.randomUUID() + "/reindex")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + id)).andExpect(status().isNoContent());
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
        awaitStatus(id, "INDEXED");
    }

    /** Polls until the document reaches {@code expected}; fails fast if it lands in the other terminal state. */
    private void awaitStatus(String id, String expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            MvcResult r = mvc.perform(get("/api/v1/documents/" + id)).andReturn();
            JsonNode node = json.readTree(r.getResponse().getContentAsString());
            String status = node.get("status").asText();
            if (status.equals(expected)) return;
            if (!status.equals("PENDING")) {
                throw new AssertionError("document " + id + " reached " + status + " (error=" + node.get("error") + "), expected " + expected);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("document " + id + " never reached " + expected);
    }
}
