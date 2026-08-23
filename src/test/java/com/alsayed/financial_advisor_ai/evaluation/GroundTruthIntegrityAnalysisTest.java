package com.alsayed.financial_advisor_ai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class GroundTruthIntegrityAnalysisTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void search381AcrossEntireCorpusAndDocument() throws Exception {
        System.out.println("=== SEARCHING FOR '3.81' IN ALL SOURCES ===");

        // 1. In vector_store
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, metadata->>'page_number' as page, metadata->>'chunk_type' as type,
                       metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content
                FROM vector_store
                WHERE content LIKE '%3.81%' OR metadata::text LIKE '%3.81%'
                """);
        System.out.printf("Found %d occurrences of '3.81' in vector_store:%n", rows.size());
        for (var r : rows) {
            System.out.printf("Page: %s | Type: %s | RowEntity: %s | Table: %s | ID: %s%nContent: %s%n-------------------------%n",
                    r.get("page"), r.get("type"), r.get("row_ent"), r.get("tbl"), r.get("id"), r.get("content"));
        }

        // 2. In raw PDF
        File pdfFile = new File("src/main/resources/docs/article_thebeatoct2024.pdf");
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (text.contains("3.81")) {
                    System.out.printf("Found '3.81' in PDF on Page %d:%n%s%n-------------------------%n", p, text);
                }
            }
        }

        // 3. In article_thebeatoct2024.txt
        File txtFile = new File("src/main/resources/docs/article_thebeatoct2024.txt");
        if (txtFile.exists()) {
            List<String> lines = Files.readAllLines(txtFile.toPath());
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("3.81")) {
                    System.out.printf("Found '3.81' in txt line %d: %s%n", i + 1, lines.get(i));
                }
            }
        }
    }

    @Test
    void searchGeneralObligationAndGOAcrossEntireDocument() throws Exception {
        System.out.println("=== SEARCHING FOR 'General Obligation' / 'GO' IN ALL SOURCES ===");

        // 1. In vector store
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, metadata->>'page_number' as page, metadata->>'chunk_type' as type,
                       metadata->>'row_entity' as row_ent, metadata->>'table_name' as tbl, content
                FROM vector_store
                WHERE content ILIKE '%General Obligation%'
                   OR content ~* '\\yGO\\y'
                """);
        System.out.printf("Found %d occurrences in vector_store:%n", rows.size());
        for (var r : rows) {
            System.out.printf("Page: %s | Type: %s | RowEntity: %s | Table: %s | ID: %s%nContent: %s%n-------------------------%n",
                    r.get("page"), r.get("type"), r.get("row_ent"), r.get("tbl"), r.get("id"), r.get("content"));
        }

        // 2. In raw PDF
        File pdfFile = new File("src/main/resources/docs/article_thebeatoct2024.pdf");
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                if (text.toLowerCase().contains("general obligation") || text.matches("(?s).*\\bGO\\b.*")) {
                    System.out.printf("Found match in PDF on Page %d:%n%s%n-------------------------%n", p, text);
                }
            }
        }
    }

    @Test
    void inspectPages20_21_22_23_27_28_DetailedTables() throws Exception {
        System.out.println("=== DETAILED TABLE AUDIT ON KEY PAGES ===");

        List<Integer> pages = List.of(20, 21, 22, 23, 27, 28);
        for (int p : pages) {
            System.out.printf("=== PAGE %d CHUNKS ===%n", p);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, metadata->>'chunk_type' as type, metadata->>'row_entity' as row_ent,
                           metadata->>'table_name' as tbl, metadata->>'parent_category' as cat, content
                    FROM vector_store
                    WHERE metadata->>'page_number' = ?
                    ORDER BY id
                    """, String.valueOf(p));
            for (var r : rows) {
                System.out.printf("ID: %s | Type: %s | Table: %s | RowEntity: %s | Cat: %s%nContent:%n%s%n-------------------------%n",
                        r.get("id"), r.get("type"), r.get("tbl"), r.get("row_ent"), r.get("cat"), r.get("content"));
            }
        }
    }
}
