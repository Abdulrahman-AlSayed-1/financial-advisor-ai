package com.alsayed.financial_advisor_ai.evaluation.deduplication;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structure-aware deduplicator that respects document metadata from structure-aware chunking.
 * Chunks with different headings or structural contexts are preserved even if text is similar.
 */
@Component
public class DocumentDeduplicator {

    private static final String HEADING_KEY = "heading";

    /**
     * Deduplicate documents while respecting structural metadata.
     * Chunks with different headings are considered distinct even if text is similar.
     *
     * @param documents The list of documents to deduplicate
     * @return List of unique documents
     */
    public List<Document> deduplicate(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }

        List<Document> uniqueDocs = new ArrayList<>();
        for (Document doc : documents) {
            String docText = doc.getText().trim();
            String docHeading = getHeading(doc);
            boolean isDuplicate = false;

            for (Document existing : uniqueDocs) {
                String existingText = existing.getText().trim();
                String existingHeading = getHeading(existing);

                // If headings differ, chunks are structurally different - keep both
                if (headingsDiffer(docHeading, existingHeading)) {
                    continue;
                }

                // Check if documents are identical
                if (docText.equals(existingText)) {
                    isDuplicate = true;
                    break;
                }

                // Check if one is a prefix of the other (handles chunks with same heading)
                if (docText.startsWith(existingText) || existingText.startsWith(docText)) {
                    // Keep the longer one
                    if (docText.length() > existingText.length()) {
                        uniqueDocs.remove(existing);
                        uniqueDocs.add(doc);
                    }
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                uniqueDocs.add(doc);
            }
        }

        return uniqueDocs;
    }

    /**
     * Extract heading from document metadata.
     */
    private String getHeading(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata == null) {
            return "";
        }
        Object heading = metadata.get(HEADING_KEY);
        return heading != null ? heading.toString().trim() : "";
    }

    /**
     * Check if two headings are meaningfully different.
     * Empty/null headings are treated as equal.
     */
    private boolean headingsDiffer(String heading1, String heading2) {
        if (heading1 == null || heading1.isEmpty()) {
            return heading2 != null && !heading2.isEmpty();
        }
        if (heading2 == null || heading2.isEmpty()) {
            return true;
        }
        return !heading1.equals(heading2);
    }
}
