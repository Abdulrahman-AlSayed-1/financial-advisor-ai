package com.alsayed.financial_advisor_ai.ingestion.extractor;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import org.springframework.core.io.Resource;

/**
 * Strategy interface for extracting raw structural and visual data from a PDF.
 */
public interface PdfExtractor {
    /**
     * Extracts raw content from the given PDF resource.
     *
     * @param pdfResource Spring Resource representing the PDF file
     * @return deserialized RawPdfDocument
     */
    RawPdfDocument extract(Resource pdfResource);
}
