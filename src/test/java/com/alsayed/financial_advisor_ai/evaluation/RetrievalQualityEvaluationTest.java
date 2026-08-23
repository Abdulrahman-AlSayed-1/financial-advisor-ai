package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quantitative evaluation test suite for retrieval quality across different financial metrics:
 * - Valuation queries (NTM P/E, P/E ratios)
 * - Return queries (Asset Class Return Analysis, 1-Mo / 3-Mo returns)
 * - Flow queries (ETF monthly flows)
 */
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class RetrievalQualityEvaluationTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private com.alsayed.financial_advisor_ai.service.IngestionService ingestionService;

    @org.junit.jupiter.api.BeforeEach
    void waitForIngestion() throws Exception {
        ingestionService.awaitIngestionComplete(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Valuation query (NTM P/E) must retrieve Valuation figure in Top-K and rank it above Return tables")
    void testValuationQueryRetrievesValuationFigureAboveReturnTable() {
        String query = "Which S&P 500 sectors had the highest and lowest NTM P/E ratios as of September 30, 2024?";
        List<Document> results = hybridRetrievalService.search(query, 10);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Retrieval should return candidates for NTM P/E valuation query");

        int valuationFigureRank = -1;
        int returnTableRank = -1;

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String title = (String) doc.getMetadata().getOrDefault("title", "");
            String figureTitle = (String) doc.getMetadata().getOrDefault("figure_title", "");
            String tableTitle = (String) doc.getMetadata().getOrDefault("table_title", "");
            String metric = (String) doc.getMetadata().getOrDefault("metric", "");
            String content = doc.getText() != null ? doc.getText() : "";

            System.out.printf("Rank #%d: [Page %s, Type: %s, Title: %s, Metric: %s]\n",
                    i + 1, doc.getMetadata().get("page_number"), doc.getMetadata().get("chunk_type"),
                    !figureTitle.isBlank() ? figureTitle : (!title.isBlank() ? title : tableTitle), metric);

            boolean isValuation = title.contains("NTM P/E") || figureTitle.contains("NTM P/E")
                    || metric.contains("NTM P/E") || content.contains("NTM P/E");
            boolean isReturnTable = tableTitle.contains("Asset Class Return") || content.contains("Asset Class Return Analysis");

            if (isValuation && valuationFigureRank == -1) {
                valuationFigureRank = i + 1;
            }
            if (isReturnTable && returnTableRank == -1) {
                returnTableRank = i + 1;
            }
        }

        assertTrue(valuationFigureRank > 0 && valuationFigureRank <= 10,
                "Valuation figure must be retrieved in Top 10 (Actual rank: " + valuationFigureRank + ")");

        if (returnTableRank != -1) {
            assertTrue(valuationFigureRank < returnTableRank,
                    String.format("Valuation figure (rank %d) must rank above unrelated Return table (rank %d) for NTM P/E query",
                            valuationFigureRank, returnTableRank));
        }
    }

    @Test
    @DisplayName("Sector Return query must retrieve Return table in Top-K and rank it above Valuation figures")
    void testSectorReturnQueryRetrievesReturnTableAboveValuationFigure() {
        String query = "Which S&P 500 sector had the highest 3-Month return as of September 30, 2024?";
        List<Document> results = hybridRetrievalService.search(query, 10);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "Retrieval should return candidates for Sector Return query");

        int returnTableRank = -1;
        int valuationFigureRank = -1;

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String title = (String) doc.getMetadata().getOrDefault("title", "");
            String figureTitle = (String) doc.getMetadata().getOrDefault("figure_title", "");
            String tableTitle = (String) doc.getMetadata().getOrDefault("table_title", "");
            String tableName = (String) doc.getMetadata().getOrDefault("table_name", "");
            String section = (String) doc.getMetadata().getOrDefault("section", "");
            String metric = (String) doc.getMetadata().getOrDefault("metric", "");
            String content = doc.getText() != null ? doc.getText() : "";

            boolean isReturnTable = tableName.contains("Return") || section.contains("Return")
                    || tableTitle.contains("Return") || title.contains("Return")
                    || figureTitle.contains("Return") || content.contains("Return Analysis")
                    || content.contains("Asset Class Return");
            boolean isValuation = title.contains("NTM P/E") || figureTitle.contains("NTM P/E")
                    || metric.contains("NTM P/E") || content.contains("NTM P/E by Sector")
                    || content.contains("Current NTM P/E");

            if (isReturnTable && returnTableRank == -1) {
                returnTableRank = i + 1;
            }
            if (isValuation && valuationFigureRank == -1) {
                valuationFigureRank = i + 1;
            }
        }

        assertTrue(returnTableRank > 0 && returnTableRank <= 5,
                "Asset Class Return table must be retrieved in Top 5 for return query (Actual rank: " + returnTableRank + ")");

        if (valuationFigureRank != -1) {
            assertTrue(returnTableRank < valuationFigureRank,
                    String.format("Return table (rank %d) must rank above Valuation figure (rank %d) for sector return query",
                            returnTableRank, valuationFigureRank));
        }
    }
}
