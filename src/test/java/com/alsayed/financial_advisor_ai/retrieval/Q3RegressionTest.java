package com.alsayed.financial_advisor_ai.retrieval;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvidenceGroup;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.EvidenceBasedRetrievalEvaluator;
import com.alsayed.financial_advisor_ai.evaluation.rag.RagSystemClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=true"
})
public class Q3RegressionTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private LexicalSearchService lexicalSearchService;

    @Autowired
    private StructuredCandidateRetriever structuredCandidateRetriever;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private EvidenceBasedRetrievalEvaluator evidenceEvaluator;

    @Autowired
    private RagSystemClient ragSystemClient;

    private static final String TARGET_CHUNK_ID = "95bf06a3-6809-3721-a627-a4bd8b0569c7";
    private static final String QUESTION_TEXT = "What was the current yield for U.S. Loans (Leveraged Loans) as of September 30, 2024?";

    @Test
    @DisplayName("Q3 Full Retrieval & Generation Chain Verification")
    void testQ3FullRetrievalAndGeneration() {
        // 1. Verify that Dense Retrieval alone ranks target chunk outside top-60
        List<Document> denseDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(QUESTION_TEXT).topK(200).similarityThreshold(0.0).build());

        int denseRank = -1;
        for (int i = 0; i < denseDocs.size(); i++) {
            if (denseDocs.get(i).getId().equals(TARGET_CHUNK_ID)) {
                denseRank = i + 1;
                break;
            }
        }
        System.out.printf("1. Dense-only rank for target chunk %s: #%d%n", TARGET_CHUNK_ID, denseRank);
        assertThat(denseRank).isGreaterThan(60);

        // 2. Verify that Lexical / Structured retrieval recovers the target chunk
        QuerySignals signals = queryAnalyzer.analyze(QUESTION_TEXT);
        System.out.printf("2. Extracted QuerySignals: entities=%s, metric=%s, period=%s, tableTypes=%s%n",
                signals.getEntities(), signals.getMetric(), signals.getPeriod(), signals.getTableTypes());

        var lexicalCandidates = lexicalSearchService.searchCorpus(QUESTION_TEXT, 50);
        var structuredCandidates = structuredCandidateRetriever.retrieveByStructuredSignals(signals, 30);

        boolean foundInLexical = lexicalCandidates.stream().anyMatch(c -> c.document().getId().equals(TARGET_CHUNK_ID));
        boolean foundInStructured = structuredCandidates.stream().anyMatch(c -> c.document().getId().equals(TARGET_CHUNK_ID));

        System.out.printf("2. Target chunk recovered in Lexical: %b | in Structured: %b%n", foundInLexical, foundInStructured);
        assertThat(foundInLexical || foundInStructured).isTrue();

        // 3. Verify Hybrid Retrieval ranks target chunk in top-3
        List<Document> retrieved = hybridRetrievalService.search(QUESTION_TEXT, 10);
        int finalRank = -1;
        Document targetRetrievedDoc = null;
        for (int i = 0; i < retrieved.size(); i++) {
            Document d = retrieved.get(i);
            System.out.printf("  [Rank #%d] ID=%s Type=%s Page=%s Prov=%s ContentSnippet=%s%n",
                    i + 1, d.getId(), d.getMetadata().get("chunk_type"), d.getMetadata().get("page_number"),
                    d.getMetadata().get("retrieval_provenance"),
                    d.getText().replace("\n", " ").substring(0, Math.min(80, d.getText().length())));
            if (d.getId().equals(TARGET_CHUNK_ID)) {
                finalRank = i + 1;
                targetRetrievedDoc = d;
            }
        }

        System.out.printf("3. Final Hybrid Retrieval rank for target chunk %s: #%d%n", TARGET_CHUNK_ID, finalRank);
        assertThat(finalRank).isBetween(1, 3);
        assertThat(targetRetrievedDoc).isNotNull();

        // 4. Verify Candidate Provenance is attached
        @SuppressWarnings("unchecked")
        Map<String, Object> provenance = (Map<String, Object>) targetRetrievedDoc.getMetadata().get("retrieval_provenance");
        System.out.printf("4. Target chunk candidate provenance: %s%n", provenance);
        assertThat(provenance).isNotNull();
        assertThat(provenance.get("sources")).isNotNull();

        // 5. Verify Content contains exact Entity: US Loans and Current: 9.54
        String content = targetRetrievedDoc.getText();
        System.out.printf("5. Target chunk content snippet:%n%s%n", content);
        assertThat(content).contains("Entity: US Loans");
        assertThat(content).contains("Current: 9.54");

        // 6. Verify Evaluator with intra-chunk requirement passes Hit@5 and Hit@10
        EvaluationQuestion evalQ = new EvaluationQuestion();
        evalQ.setQuestionText(QUESTION_TEXT);
        evalQ.setContentType("table");
        evalQ.setSourcePage(20);
        evalQ.setGroundTruth("The yield for U.S. Loans was 9.54% as of September 30, 2024.");
        evalQ.setEvidenceGroups(List.of(
                new EvidenceGroup(List.of("us loans", "current", "9.54"))
        ));

        var matchResult = evidenceEvaluator.evaluateEvidence(evalQ, retrieved, 5);
        System.out.printf("6. Evaluator Intra-Chunk Match Result (Hit@5): isHit=%b, reason=%s%n",
                matchResult.matched(), matchResult.reason());
        assertThat(matchResult.matched()).isTrue();

        // 7. Verify RAG Answer Generation
        var ragResponse = ragSystemClient.queryRagSystem(QUESTION_TEXT);
        String answer = ragResponse.getAnswer();
        System.out.printf("7. RAG Generated Answer:%n%s%n", answer);
        assertThat(answer).contains("9.54");
    }
}
