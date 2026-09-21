package dev.bhavya.anvesh.ingest;

import dev.bhavya.anvesh.cache.SearchCacheService;
import dev.bhavya.anvesh.common.ConflictException;
import dev.bhavya.anvesh.document.Document;
import dev.bhavya.anvesh.document.DocumentRepository;
import dev.bhavya.anvesh.embedding.EmbeddingService;
import dev.bhavya.anvesh.embedding.HashEmbeddingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests: no database. We mock the repository and the PlatformTransactionManager so we can
 * assert the *shape* of the transaction (what's inside it, commit vs rollback) without Docker.
 * The real rollback behaviour against Postgres is covered by AnveshIntegrationTest.
 */
class IngestServiceTest {

    DocumentRepository repo;
    PlatformTransactionManager txm;
    SearchCacheService cache;
    IngestService service;
    UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        txm = mock(PlatformTransactionManager.class);
        cache = mock(SearchCacheService.class);
        when(txm.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        service = new IngestService(repo, new TextChunker(800, 100),
                new HashEmbeddingService(384), new TransactionTemplate(txm), new SimpleMeterRegistry(), cache);
    }

    @Test
    void submit_newDocument_isQueued() {
        when(repo.insertOrGetExisting(any(), any(), any(), any(), any(), any(), any())).thenReturn(new DocumentRepository.Upsert(id, true));
        IngestService.Submission s = service.submit("t", null, "en", "{}", "body");
        assertThat(s.duplicate()).isFalse();
        assertThat(s.needsIndex()).isTrue();
        assertThat(s.status()).isEqualTo(Document.Status.PENDING);
        verify(repo, never()).markPendingIfFailed(any());
    }

    @Test
    void submit_duplicateStillPending_isNotQueuedAgain() {
        when(repo.insertOrGetExisting(any(), any(), any(), any(), any(), any(), any())).thenReturn(new DocumentRepository.Upsert(id, false));
        when(repo.markPendingIfFailed(id)).thenReturn(false);
        when(repo.findById(id)).thenReturn(Optional.of(doc(Document.Status.PENDING)));
        IngestService.Submission s = service.submit("t", null, "en", "{}", "body");
        assertThat(s.duplicate()).isTrue();
        assertThat(s.needsIndex()).isFalse();
        assertThat(s.status()).isEqualTo(Document.Status.PENDING);
    }

    @Test
    void submit_duplicateThatFailed_isRetriedOnce() {
        when(repo.insertOrGetExisting(any(), any(), any(), any(), any(), any(), any())).thenReturn(new DocumentRepository.Upsert(id, false));
        when(repo.markPendingIfFailed(id)).thenReturn(true);
        IngestService.Submission s = service.submit("t", null, "en", "{}", "body");
        assertThat(s.duplicate()).isTrue();
        assertThat(s.needsIndex()).isTrue();
        assertThat(s.status()).isEqualTo(Document.Status.PENDING);
    }

    @Test
    void happyPath_locksDeletesInsertsMarksIndexed_thenCommits() {
        service.index(id, "Some body text that will become exactly one chunk.");

        InOrder order = inOrder(repo, txm);
        order.verify(txm).getTransaction(any());
        order.verify(repo).lockForIndexing(id);
        order.verify(repo).deleteChunks(id);
        order.verify(repo).insertChunks(eq(id), anyList(), anyList());
        order.verify(repo).markIndexed(id);
        order.verify(txm).commit(any(TransactionStatus.class));
        verify(txm, never()).rollback(any());
        verify(repo, never()).markFailed(any(), any());
    }

    @Test
    void whenMarkIndexedThrows_transactionRollsBack_andDocIsMarkedFailedOutsideIt() {
        doThrow(new RuntimeException("connection reset")).when(repo).markIndexed(id);

        service.index(id, "Some body text.");

        InOrder order = inOrder(repo, txm);
        order.verify(txm).getTransaction(any());
        order.verify(repo).insertChunks(eq(id), anyList(), anyList());
        order.verify(txm).rollback(any(TransactionStatus.class));
        order.verify(repo).markFailed(eq(id), contains("connection reset"));
        verify(txm, never()).commit(any());
    }

    @Test
    void embeddingHappensBeforeTransactionOpens() {
        EmbeddingService exploding = mock(EmbeddingService.class);
        when(exploding.embedAll(anyList())).thenThrow(new IllegalStateException("model exploded"));
        IngestService broken = new IngestService(repo, new TextChunker(800, 100),
                exploding, new TransactionTemplate(txm), new SimpleMeterRegistry(), cache);

        broken.index(id, "body");

        verify(txm, never()).getTransaction(any());
        verify(repo, never()).deleteChunks(any());
        verify(repo).markFailed(eq(id), contains("model exploded"));
    }

    @Test
    void reindex_isRejectedWhenAnotherIndexerAlreadyWon() {
        when(repo.findById(id)).thenReturn(Optional.of(doc(Document.Status.PENDING)));
        when(repo.findByIdAndOwner(any(), any())).thenReturn(Optional.of(doc(Document.Status.PENDING)));
        when(repo.findBody(id)).thenReturn(Optional.of("body"));
        when(repo.markPendingForReindex(id)).thenReturn(false);

        assertThatThrownBy(() -> service.reindex(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already being indexed");
        verify(txm, never()).getTransaction(any());
    }

    @Test
    void reindex_rejectsDocumentsWithoutStoredBody() {
        when(repo.findById(id)).thenReturn(Optional.of(doc(Document.Status.INDEXED)));
        when(repo.findByIdAndOwner(any(), any())).thenReturn(Optional.of(doc(Document.Status.INDEXED)));
        when(repo.findBody(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reindex(id)).isInstanceOf(ConflictException.class);
        verify(repo, never()).markPendingForReindex(any());
    }

    private Document doc(Document.Status status) {
        return new Document(id, "t", null, "en", "{}", "hash", status, null, Instant.now(), null, "public");
    }

    @Test
    void sha256IsStable() {
        assertThat(IngestService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
