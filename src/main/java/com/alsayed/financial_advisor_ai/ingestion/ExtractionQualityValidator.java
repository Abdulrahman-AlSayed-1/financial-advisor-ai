package com.alsayed.financial_advisor_ai.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates the quality of PDF extraction before proceeding to embedding.
 * Implements quality gates as recommended in production RAG systems.
 */
@Component
public class ExtractionQualityValidator {

    private static final Logger logger = LoggerFactory.getLogger(ExtractionQualityValidator.class);

    // Quality thresholds
    private static final double MIN_TEXT_RATIO = 0.3; // At least 30% of elements should have text

    /**
     * Validates extraction quality across all documents.
     * Returns true if quality passes thresholds, false otherwise.
     */
    public ExtractionQualityReport validate(List<Document> documents) {
        ExtractionQualityReport report = new ExtractionQualityReport();

        logger.info("=== Starting Extraction Quality Validation ===");
        logger.info("Total documents: {}", documents.size());

        // Validate page count
        validatePageCount(documents, report);

        // Validate text extraction ratio
        validateTextExtractionRatio(documents, report);

        // Validate table extraction quality
        validateTableExtractionQuality(documents, report);

        // Validate figure extraction quality
        validateFigureExtractionQuality(documents, report);

        // Validate metadata completeness
        validateMetadataCompleteness(documents, report);

        // Calculate overall score
        report.calculateOverallScore();

        logger.info("=== Extraction Quality Validation Complete ===");
        logger.info("Overall Status: {}", report.isPassed() ? "PASSED" : "FAILED");
        logger.info("Quality Score: {}/100", report.getQualityScore());

        return report;
    }

    private void validatePageCount(List<Document> documents, ExtractionQualityReport report) {
        int actualPageCount = countUniquePages(documents);
        
        report.setActualPageCount(actualPageCount);

        // Validate that pages were actually extracted
        // No hard-coded page count assumptions - just detect extraction failures
        boolean pageCountValid = actualPageCount > 0 && 
                               countValidPages(documents) > 0;

        report.setPageCountValid(pageCountValid);

        if (!pageCountValid) {
            logger.warn("Page count validation failed: no valid pages detected (total unique pages: {})", actualPageCount);
        } else {
            logger.info("Page count validation passed: {} unique pages extracted", actualPageCount);
        }
    }
    
    private int countValidPages(List<Document> documents) {
        return (int) documents.stream()
            .map(doc -> doc.getMetadata().get("page_number"))
            .filter(page -> page != null)
            .map(page -> ((Number) page).intValue())
            .filter(page -> page > 0) // Only count positive page numbers
            .count();
    }

    private void validateTextExtractionRatio(List<Document> documents, ExtractionQualityReport report) {
        int elementsWithText = 0;
        int totalElements = documents.size();

        for (Document doc : documents) {
            String text = doc.getText();
            // Treat element as having text if non-null and non-blank
            // Short financial content like "P/E Ratio: 18.7" is meaningful
            if (text != null && !text.trim().isEmpty()) {
                elementsWithText++;
            }
        }

        double textRatio = totalElements > 0 ? (double) elementsWithText / totalElements : 0;
        report.setTextExtractionRatio(textRatio);

        boolean textRatioValid = textRatio >= MIN_TEXT_RATIO;
        report.setTextExtractionValid(textRatioValid);

        if (!textRatioValid) {
            logger.warn("Text extraction ratio too low: {} (threshold: {})", textRatio, MIN_TEXT_RATIO);
        } else {
            logger.info("Text extraction ratio valid: {} elements with text out of {}", elementsWithText, totalElements);
        }
    }

    private void validateTableExtractionQuality(List<Document> documents, ExtractionQualityReport report) {
        int tableDocuments = 0;
        int tablesWithValidStructure = 0;
        int tablesWithInvalidStructure = 0;

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            String chunkType = (String) metadata.get("chunk_type");

            if ("table".equals(chunkType) || "correlation_table".equals(chunkType)) {
                tableDocuments++;
                
                // Check for per-cell chunking metadata (row_entity, metric, period, value)
                // This is the new structure after CanonicalTableChunker changes
                boolean hasRowEntity = metadata.containsKey("row_entity") && 
                                     metadata.get("row_entity") != null &&
                                     !metadata.get("row_entity").toString().trim().isEmpty();
                boolean hasValue = metadata.containsKey("value") && 
                                 metadata.get("value") != null &&
                                 !metadata.get("value").toString().trim().isEmpty();
                
                // Table is structurally valid if it has at least row_entity and value
                // (metric and period are optional for some table types)
                if (hasRowEntity && hasValue) {
                    tablesWithValidStructure++;
                } else {
                    tablesWithInvalidStructure++;
                }
            }
        }

        report.setTableCount(tableDocuments);
        
        // Calculate ratio of tables with valid structure
        double structureRatio = tableDocuments > 0 ? (double) tablesWithValidStructure / tableDocuments : 0;
        report.setTableStructureRatio(structureRatio);

        boolean tableQualityValid = tableDocuments > 0 && structureRatio >= 0.5; // At least 50% have valid structure
        report.setTableQualityValid(tableQualityValid);

        if (!tableQualityValid) {
            logger.warn("Table extraction quality issue: {} tables without valid structure out of {} (structure ratio: {})", 
                tablesWithInvalidStructure, tableDocuments, structureRatio);
        } else {
            logger.info("Table extraction quality valid: {} tables extracted, {} with valid structure (ratio: {})", 
                tableDocuments, tablesWithValidStructure, structureRatio);
        }
    }

    private void validateFigureExtractionQuality(List<Document> documents, ExtractionQualityReport report) {
        int figureDocuments = 0;
        int figuresWithValidStructure = 0;
        int figuresWithInvalidStructure = 0;

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            String chunkType = (String) metadata.get("chunk_type");

            if ("figure".equals(chunkType)) {
                figureDocuments++;
                
                // Check for figure-specific metadata (entity, value, series, etc.)
                // This is the new structure after FigureParser integration
                boolean hasEntity = metadata.containsKey("entity") && 
                                   metadata.get("entity") != null &&
                                   !metadata.get("entity").toString().trim().isEmpty();
                boolean hasValue = metadata.containsKey("value") && 
                                 metadata.get("value") != null &&
                                 !metadata.get("value").toString().trim().isEmpty();
                
                // Figure is structurally valid if it has at least entity or value
                // (series, panel, metric, period, unit are optional)
                if (hasEntity || hasValue) {
                    figuresWithValidStructure++;
                } else {
                    figuresWithInvalidStructure++;
                }
            }
        }

        report.setFigureCount(figureDocuments);
        
        // Calculate ratio of figures with valid structure
        double structureRatio = figureDocuments > 0 ? (double) figuresWithValidStructure / figureDocuments : 0;
        report.setFigureStructureRatio(structureRatio);

        boolean figureQualityValid = figureDocuments > 0 && structureRatio >= 0.3; // At least 30% have valid structure (lower threshold for figures)
        report.setFigureQualityValid(figureQualityValid);

        if (!figureQualityValid) {
            logger.warn("Figure extraction quality issue: {} figures without valid structure out of {} (structure ratio: {})", 
                figuresWithInvalidStructure, figureDocuments, structureRatio);
        } else {
            logger.info("Figure extraction quality valid: {} figures extracted, {} with valid structure (ratio: {})", 
                figureDocuments, figuresWithValidStructure, structureRatio);
        }
    }

    private void validateMetadataCompleteness(List<Document> documents, ExtractionQualityReport report) {
        int documentsWithMetadata = 0;
        int documentsWithPageNumber = 0;
        int documentsWithChunkType = 0;
        int documentsWithTableMetadata = 0;

        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            if (!metadata.isEmpty()) {
                documentsWithMetadata++;
            }
            if (metadata.containsKey("page_number")) {
                documentsWithPageNumber++;
            }
            if (metadata.containsKey("chunk_type")) {
                documentsWithChunkType++;
            }
            // Check for table-specific metadata (row_entity, metric, period, value)
            if (metadata.containsKey("row_entity") || metadata.containsKey("metric") || 
                metadata.containsKey("period") || metadata.containsKey("value")) {
                documentsWithTableMetadata++;
            }
        }

        double metadataRatio = documents.size() > 0 ? (double) documentsWithMetadata / documents.size() : 0;
        double pageMetadataRatio = documents.size() > 0 ? (double) documentsWithPageNumber / documents.size() : 0;
        double chunkTypeRatio = documents.size() > 0 ? (double) documentsWithChunkType / documents.size() : 0;
        double tableMetadataRatio = documents.size() > 0 ? (double) documentsWithTableMetadata / documents.size() : 0;

        report.setMetadataCompletenessRatio(metadataRatio);
        report.setPageMetadataRatio(pageMetadataRatio);

        // Validate that core metadata is present
        // Updated for per-cell chunking: chunk_type is more relevant than element_type/element_index
        boolean metadataValid = metadataRatio >= 0.8 && pageMetadataRatio >= 0.7 && 
                               chunkTypeRatio >= 0.8; // Removed element_type/element_index requirements
        report.setMetadataValid(metadataValid);

        if (!metadataValid) {
            logger.warn("Metadata completeness issue: metadata ratio {}, page metadata ratio {}, chunk_type ratio {}, table metadata ratio {}", 
                metadataRatio, pageMetadataRatio, chunkTypeRatio, tableMetadataRatio);
        } else {
            logger.info("Metadata completeness valid: {} documents with metadata, {} with page numbers, {} with chunk_type, {} with table metadata", 
                documentsWithMetadata, documentsWithPageNumber, documentsWithChunkType, documentsWithTableMetadata);
        }
    }

    private int countUniquePages(List<Document> documents) {
        return (int) documents.stream()
            .map(doc -> doc.getMetadata().get("page_number"))
            .filter(page -> page != null)
            .map(page -> ((Number) page).intValue())
            .distinct()
            .count();
    }

    /**
     * Quality report containing all validation results.
     */
    public static class ExtractionQualityReport {
        private boolean passed;
        private int qualityScore;
        private int actualPageCount;
        private boolean pageCountValid;
        private double textExtractionRatio;
        private boolean textExtractionValid;
        private int tableCount;
        private double tableStructureRatio;
        private boolean tableQualityValid;
        private int figureCount;
        private double figureStructureRatio;
        private boolean figureQualityValid;
        private double metadataCompletenessRatio;
        private double pageMetadataRatio;
        private boolean metadataValid;

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public int getQualityScore() {
            return qualityScore;
        }

        public void setQualityScore(int qualityScore) {
            this.qualityScore = qualityScore;
        }

        public int getActualPageCount() {
            return actualPageCount;
        }

        public void setActualPageCount(int actualPageCount) {
            this.actualPageCount = actualPageCount;
        }

        public boolean isPageCountValid() {
            return pageCountValid;
        }

        public void setPageCountValid(boolean pageCountValid) {
            this.pageCountValid = pageCountValid;
        }

        public double getTextExtractionRatio() {
            return textExtractionRatio;
        }

        public void setTextExtractionRatio(double textExtractionRatio) {
            this.textExtractionRatio = textExtractionRatio;
        }

        public boolean isTextExtractionValid() {
            return textExtractionValid;
        }

        public void setTextExtractionValid(boolean textExtractionValid) {
            this.textExtractionValid = textExtractionValid;
        }

        public int getTableCount() {
            return tableCount;
        }

        public void setTableCount(int tableCount) {
            this.tableCount = tableCount;
        }

        public double getTableStructureRatio() {
            return tableStructureRatio;
        }

        public void setTableStructureRatio(double tableStructureRatio) {
            this.tableStructureRatio = tableStructureRatio;
        }

        public boolean isTableQualityValid() {
            return tableQualityValid;
        }

        public void setTableQualityValid(boolean tableQualityValid) {
            this.tableQualityValid = tableQualityValid;
        }

        public int getFigureCount() {
            return figureCount;
        }

        public void setFigureCount(int figureCount) {
            this.figureCount = figureCount;
        }

        public double getFigureStructureRatio() {
            return figureStructureRatio;
        }

        public void setFigureStructureRatio(double figureStructureRatio) {
            this.figureStructureRatio = figureStructureRatio;
        }

        public boolean isFigureQualityValid() {
            return figureQualityValid;
        }

        public void setFigureQualityValid(boolean figureQualityValid) {
            this.figureQualityValid = figureQualityValid;
        }

        public double getMetadataCompletenessRatio() {
            return metadataCompletenessRatio;
        }

        public void setMetadataCompletenessRatio(double metadataCompletenessRatio) {
            this.metadataCompletenessRatio = metadataCompletenessRatio;
        }

        public double getPageMetadataRatio() {
            return pageMetadataRatio;
        }

        public void setPageMetadataRatio(double pageMetadataRatio) {
            this.pageMetadataRatio = pageMetadataRatio;
        }

        public boolean isMetadataValid() {
            return metadataValid;
        }

        public void setMetadataValid(boolean metadataValid) {
            this.metadataValid = metadataValid;
        }

        /**
         * Calculate overall quality score (0-100) and pass/fail status.
         */
        public void calculateOverallScore() {
            int score = 0;

            if (pageCountValid) score += 20;
            if (textExtractionValid) score += 20;
            if (tableQualityValid) score += 20;
            if (figureQualityValid) score += 20;
            if (metadataValid) score += 20;

            this.qualityScore = score;
            this.passed = score >= 80; // Pass if 80% or higher (5 criteria now)
        }
    }
}
