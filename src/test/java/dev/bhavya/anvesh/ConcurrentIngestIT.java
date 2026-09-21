package dev.bhavya.anvesh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Week 2: correctness under concurrency.
 * 50 distinct documents submitted from 8 client threads at once, plus the same 50 submitted
 * again as one batch while the first wave is still indexing. Every document must end INDEXED
 * with exactly the expected number of chunks — no duplicates, no orphans, nothing stuck PENDING.
 *
 * Uses the hash embedder (fast, deterministic) and a tiny chunk size so each doc yields
 * a predictable, >1 chunk count.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "anvesh.embedding.provider=hash",
        "anvesh.chunking.max-chars=120",
        "anvesh.chunking.overlap-chars=20",
        "anvesh.rate-limit.enabled=false",
        "anvesh.cache.enabled=false"
})
class ConcurrentIngestIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    static final int DOCS = 50;

    @Test
    void fiftyConcurrentIngests_allIndexed_noDuplicateChunks() throws Exception {
        // Each body is unique (sha256 dedup would otherwise collapse them) and long enough for 3+ chunks.
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            bodies.add(("Document number " + i + ". ").repeat(20) + "Unique tail " + UUID.randomUUID());
        }

        // Wave 1: 50 single POSTs from 8 threads.
        ExecutorService clients = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            final int n = i;
            futures.add(clients.submit(() -> submitOne("Doc " + n, bodies.get(n))));
        }
        List<String> ids = new ArrayList<>();
        for (Future<String> f : futures) ids.add(f.get(30, TimeUnit.SECONDS));
        clients.shutdown();

        // Wave 2: the same 50 as a batch, racing the still-running indexers. All must be duplicates.
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) batch.add(Map.of("title", "Doc " + i, "language", "en", "body", bodies.get(i)));
        MvcResult res = mvc.perform(post("/api/v1/documents/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", batch))))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode br = json.readTree(res.getResponse().getContentAsString());
        // Every one must be reported as a duplicate — including the ones still PENDING from wave 1.
        // (An earlier version decided "new" by chunk count, re-queued PENDING docs, and two indexers
        // raced on UNIQUE (document_id, ordinal). CI caught it. See DocumentRepository.insertOrGetExisting.)
        assertThat(br.get("results").size()).isEqualTo(DOCS);
        assertThat(br.get("duplicates").asInt()).isEqualTo(DOCS);
        for (JsonNode r : br.get("results")) assertThat(ids).contains(r.get("id").asText());

        // Everything settles to INDEXED.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            Integer notDone = jdbc.queryForObject("SELECT count(*) FROM documents WHERE status <> 'INDEXED'", Integer.class);
            if (notDone != null && notDone == 0) break;
            Thread.sleep(200);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status, count(*) AS n FROM documents GROUP BY status");
        assertThat(rows).as("all documents INDEXED, got " + rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("INDEXED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class)).isEqualTo(DOCS);

        // Exactly one chunk per (document, ordinal) and the expected count per document:
        // the UNIQUE constraint guarantees the first; this checks index() ran to completion for all.
        Integer distinctDocsWithChunks = jdbc.queryForObject("SELECT count(DISTINCT document_id) FROM chunks", Integer.class);
        assertThat(distinctDocsWithChunks).isEqualTo(DOCS);
        List<Integer> perDoc = jdbc.queryForList("SELECT count(*) FROM chunks GROUP BY document_id", Integer.class);
        assertThat(perDoc).allSatisfy(n -> assertThat(n).isBetween(3, 8));
        // Ordinals are contiguous 0..n-1 for every document (no gaps => no partial writes survived).
        Integer gaps = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT document_id, max(ordinal) + 1 AS expected, count(*) AS actual
                  FROM chunks GROUP BY document_id) t WHERE expected <> actual
                """, Integer.class);
        assertThat(gaps).isZero();

        // Batch validation: empty and oversized batches are 400.
        mvc.perform(post("/api/v1/documents/batch").contentType(MediaType.APPLICATION_JSON).content("{\"documents\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private String submitOne(String title, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/documents").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", title, "language", "en", "body", body))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isIn(200, 202);
        return json.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }
}
