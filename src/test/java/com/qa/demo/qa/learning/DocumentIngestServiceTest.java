package com.qa.demo.qa.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIngestServiceTest {

    @Test
    void splitIntoChunks_splitsByParagraph() {
        var chunks = DocumentIngestService.splitIntoChunks(
                "第一段内容。\n\n第二段内容。",
                "note.md"
        );
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contentText().contains("第一段"));
        assertTrue(chunks.get(1).contentText().contains("第二段"));
    }

    @Test
    void splitIntoChunks_skipsBlank() {
        assertTrue(DocumentIngestService.splitIntoChunks("   \n\n  ", "empty.txt").isEmpty());
    }

    @Test
    void splitIntoChunks_largeParagraphSlices() {
        String big = "A".repeat(5000);
        var chunks = DocumentIngestService.splitIntoChunks(big, "big.txt");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.contentText().length() <= 4000));
    }
}
