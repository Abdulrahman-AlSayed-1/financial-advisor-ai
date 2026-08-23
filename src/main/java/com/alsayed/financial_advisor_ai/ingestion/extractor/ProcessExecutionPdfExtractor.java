package com.alsayed.financial_advisor_ai.ingestion.extractor;

import com.alsayed.financial_advisor_ai.model.raw.RawPdfDocument;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Production runtime extractor that executes the PyMuPDF python extraction script on the fly.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ingestion.extractor-mode", havingValue = "process")
public class ProcessExecutionPdfExtractor implements PdfExtractor {

    private final ObjectMapper objectMapper;

    @Value("${app.ingestion.python-executable:python3}")
    private String pythonExecutable;

    @Value("${app.ingestion.script-path:scripts/pymupdf_extractor.py}")
    private String scriptPath;

    public ProcessExecutionPdfExtractor() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public RawPdfDocument extract(Resource pdfResource) {
        try {
            File pdfFile = pdfResource.getFile();
            Path tempJson = Files.createTempFile("pymupdf_ext_", ".json");
            Path tempDir = Files.createTempDirectory("pymupdf_media_");

            log.info("Executing PyMuPDF extractor on {}", pdfFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    scriptPath,
                    "--input", pdfFile.getAbsolutePath(),
                    "--output-json", tempJson.toAbsolutePath().toString(),
                    "--output-dir", tempDir.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String processOutput = new String(process.getInputStream().readAllBytes());
                log.error("PyMuPDF extractor process failed with code {}: {}", exitCode, processOutput);
                throw new RuntimeException("PyMuPDF extraction failed: " + processOutput);
            }

            try (InputStream is = new FileInputStream(tempJson.toFile())) {
                RawPdfDocument doc = objectMapper.readValue(is, RawPdfDocument.class);
                log.info("PyMuPDF process extraction complete ({} pages)", doc.totalPages());
                return doc;
            }
        } catch (Exception e) {
            log.error("Failed to execute PyMuPDF extraction: {}", e.getMessage(), e);
            throw new RuntimeException("ProcessExecutionPdfExtractor failed", e);
        }
    }
}
