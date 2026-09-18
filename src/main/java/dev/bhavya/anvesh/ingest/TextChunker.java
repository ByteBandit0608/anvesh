package dev.bhavya.anvesh.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into overlapping windows, preferring to cut at paragraph / sentence boundaries.
 *
 * <p>Why chunk at all: embedding models have a token limit (256 for MiniLM) and a single vector
 * for a 10-page doc is mush. Overlap keeps a sentence that straddles a boundary retrievable
 * from either side.
 *
 * <p>Sentence terminators include the Devanagari/Telugu danda (।) since Telugu prose uses it.
 */
public class TextChunker {

    private static final String BREAK_CHARS = ".!?।\n";

    private final int maxChars;
    private final int overlapChars;

    public TextChunker(int maxChars, int overlapChars) {
        if (overlapChars >= maxChars) throw new IllegalArgumentException("overlap must be < maxChars");
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.strip();
        if (s.isEmpty()) return out;

        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + maxChars, s.length());

            if (end < s.length()) {
                int cut = findBreak(s, start, end);
                if (cut > start) end = cut;
            }

            String piece = s.substring(start, end).strip();
            if (!piece.isEmpty()) out.add(piece);

            if (end >= s.length()) break;
            // Step back for overlap, but always make forward progress.
            int next = end - overlapChars;
            start = Math.max(next, start + 1);
        }
        return out;
    }

    /** Last sentence/paragraph boundary in (start + maxChars/2, end]; -1 if none. */
    private int findBreak(String s, int start, int end) {
        int floor = start + maxChars / 2;
        for (int i = end - 1; i > floor; i--) {
            if (BREAK_CHARS.indexOf(s.charAt(i)) >= 0) return i + 1;
        }
        // No sentence boundary — fall back to whitespace so we don't split a word.
        for (int i = end - 1; i > floor; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i + 1;
        }
        return -1;
    }
}
