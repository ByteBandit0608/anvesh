package dev.bhavya.anvesh.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void shortTextIsSingleChunk() {
        assertThat(new TextChunker(100, 10).chunk("hello world")).containsExactly("hello world");
    }

    @Test
    void emptyOrNullYieldsNothing() {
        TextChunker c = new TextChunker(100, 10);
        assertThat(c.chunk(null)).isEmpty();
        assertThat(c.chunk("   \n ")).isEmpty();
    }

    @Test
    void prefersSentenceBoundaries() {
        String text = "First sentence here. Second sentence follows. Third one is last.";
        List<String> chunks = new TextChunker(45, 5).chunk(text);
        assertThat(chunks).allSatisfy(ch -> assertThat(ch.length()).isLessThanOrEqualTo(45));
        assertThat(chunks.get(0)).endsWith(".");
    }

    @Test
    void overlappingChunksShareText() {
        String text = "a".repeat(50) + " " + "b".repeat(50) + " " + "c".repeat(50);
        List<String> chunks = new TextChunker(60, 20).chunk(text);
        assertThat(chunks.size()).isGreaterThan(1);
        // Every character of the source appears in at least one chunk.
        assertThat(String.join("", chunks).replace(" ", "")).contains("a".repeat(50), "b".repeat(50), "c".repeat(50));
    }

    @Test
    void handlesTeluguDanda() {
        String text = "తెలుగు వాక్యం ఒకటి। తెలుగు వాక్యం రెండు। తెలుగు వాక్యం మూడు।";
        List<String> chunks = new TextChunker(40, 5).chunk(text);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0)).endsWith("।");
    }

    @Test
    void alwaysMakesProgressOnPathologicalInput() {
        String noBreaks = "x".repeat(5000);
        List<String> chunks = new TextChunker(100, 90).chunk(noBreaks);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isLessThan(5000);
    }

    @Test
    void rejectsOverlapLargerThanWindow() {
        assertThatThrownBy(() -> new TextChunker(10, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
