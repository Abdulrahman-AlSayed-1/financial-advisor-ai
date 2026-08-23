package com.alsayed.financial_advisor_ai.ingestion.extractor;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Extractor implementation that reads pre-extracted RawPdfDocument JSON from disk/classpath.
 * Used during migration, rapid testing, and deterministic evaluation.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ingestion.extractor-mode", havingValue = "file", matchIfMissing = true)
public class JsonFilePdfExtractor implements PdfExtractor {

    private final ObjectMapper objectMapper;

    @Value("${app.ingestion.pymupdf.json-resource:classpath:/docs/pymupdf_extraction.json}")
    private Resource jsonResource;

    public JsonFilePdfExtractor() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public JsonFilePdfExtractor(ObjectMapper objectMapper, Resource jsonResource) {
        this.objectMapper = objectMapper;
        this.jsonResource = jsonResource;
    }

    @Override
    public RawPdfDocument extract(Resource pdfResource) {
        Resource targetResource = jsonResource != null ? jsonResource : new org.springframework.core.io.ClassPathResource("docs/pymupdf_extraction.json");
        log.info("Loading PyMuPDF extraction JSON from resource: {}", targetResource);
        try (InputStream is = targetResource.getInputStream()) {
            RawPdfDocument doc = objectMapper.readValue(is, RawPdfDocument.class);
            log.info("Successfully loaded RawPdfDocument with {} pages from {}", doc.totalPages(), doc.sourceFile());
            return doc;
        } catch (Exception e) {
            log.error("Failed to read RawPdfDocument from {}: {}", targetResource, e.getMessage(), e);
            throw new RuntimeException("Failed to read PyMuPDF extraction JSON", e);
        }
    }
}
