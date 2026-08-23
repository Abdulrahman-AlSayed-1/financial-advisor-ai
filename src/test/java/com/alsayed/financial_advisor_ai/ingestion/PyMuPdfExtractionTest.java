package com.alsayed.financial_advisor_ai.ingestion;

import com.alsayed.financial_advisor_ai.ingestion.extractor.JsonFilePdfExtractor;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.alsayed.financial_advisor_ai.model.raw.RawPdfPage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class PyMuPdfExtractionTest {

    @Test
    void testExtractDocumentFromJson() {
        JsonFilePdfExtractor extractor = new JsonFilePdfExtractor();
        RawPdfDocument doc = extractor.extract(new ClassPathResource("docs/article_thebeatoct2024.pdf"));

        assertNotNull(doc, "RawPdfDocument should not be null");
        assertTrue(doc.totalPages() > 0, "Total pages should be greater than 0");
        assertNotNull(doc.pages(), "Pages list should not be null");
        assertEquals(doc.totalPages(), doc.pages().size());

        // Verify page 1
        RawPdfPage page1 = doc.pages().get(0);
        assertEquals(1, page1.pageNumber());
        assertTrue(page1.width() > 0);
        assertTrue(page1.height() > 0);
        assertNotNull(page1.textBlocks());
        assertFalse(page1.textBlocks().isEmpty(), "Page 1 should have text blocks");

        // Verify canonical coordinate convention (Top-Left origin: y0 <= y1, x0 <= x1)
        var firstBlock = page1.textBlocks().get(0);
        assertNotNull(firstBlock.bbox());
        assertEquals(4, firstBlock.bbox().length);
        assertTrue(firstBlock.getX0() <= firstBlock.getX1(), "x0 must be <= x1");
        assertTrue(firstBlock.getY0() <= firstBlock.getY1(), "y0 must be <= y1 in canonical top-left coordinates");
    }
}
