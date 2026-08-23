package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.evaluators.EvidenceBasedRetrievalEvaluator;
import com.alsayed.financial_advisor_ai.evaluation.rag.RagSystemClient;
import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
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
        "app.rag.search.hybrid.enabled=true"
})
public class BenchmarkAllQuestionsTest {

    @Autowired
    private EvaluationDatasetLoader datasetLoader;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private EvidenceBasedRetrievalEvaluator evidenceEvaluator;

    @Autowired
    private RagSystemClient ragSystemClient;



    @Test
    @DisplayName("Run Full Q1-Q11 Benchmark Evaluation with Atomic Evidence Verification")
    void testFullBenchmark() {
        List<EvaluationQuestion> questions = datasetLoader.loadDataset();
        System.out.println("=========================================================================================");
        System.out.printf("RUNNING FULL BENCHMARK OVER %d QUESTIONS%n", questions.size());
        System.out.println("=========================================================================================");

        int hitAt1Count = 0;
        int hitAt3Count = 0;
        int hitAt5Count = 0;
        int hitAt10Count = 0;
        long totalRetrievalLatencyMs = 0;
        long totalRagLatencyMs = 0;

        for (int i = 0; i < questions.size(); i++) {
            EvaluationQuestion q = questions.get(i);
            String qId = q.getId() != null ? q.getId() : ("Q" + (i + 1));
            String qText = q.getQuestionText();

            long startRet = System.currentTimeMillis();
            List<Document> retrieved = hybridRetrievalService.search(qText, 10);
            long retLatency = System.currentTimeMillis() - startRet;
            totalRetrievalLatencyMs += retLatency;

            var hit1 = evidenceEvaluator.evaluateEvidence(q, retrieved, 1);
            var hit3 = evidenceEvaluator.evaluateEvidence(q, retrieved, 3);
            var hit5 = evidenceEvaluator.evaluateEvidence(q, retrieved, 5);
            var hit10 = evidenceEvaluator.evaluateEvidence(q, retrieved, 10);

            if (hit1.matched()) hitAt1Count++;
            if (hit3.matched()) hitAt3Count++;
            if (hit5.matched()) hitAt5Count++;
            if (hit10.matched()) hitAt10Count++;

            long startRag = System.currentTimeMillis();
            var ragResponse = ragSystemClient.queryRagSystem(qText);
            long ragLatency = System.currentTimeMillis() - startRag;
            totalRagLatencyMs += ragLatency;

            System.out.println("-----------------------------------------------------------------------------------------");
            System.out.printf("[%s] (%s) Page: %s | Type: %s%n", qId, q.getContentType(), q.getSourcePage(), q.getQuestionText());
            System.out.printf("  Hit@1: %b | Hit@3: %b | Hit@5: %b | Hit@10: %b%n",
                    hit1.matched(), hit3.matched(), hit5.matched(), hit10.matched());
            System.out.printf("  Ground Truth: %s%n", q.getGroundTruth());
            System.out.printf("  Generated Answer: %s%n", ragResponse.getAnswer().replace("\n", " "));
            System.out.printf("  Retrieval Latency: %dms | RAG Latency: %dms%n", retLatency, ragLatency);

            // Print top retrieved chunk details and provenance
            for (int k = 0; k < Math.min(3, retrieved.size()); k++) {
                Document doc = retrieved.get(k);
                @SuppressWarnings("unchecked")
                Map<String, Object> prov = (Map<String, Object>) doc.getMetadata().get("retrieval_provenance");
                System.out.printf("    Chunk #%d [ID=%s, Page=%s, Type=%s]: Sources=%s, RRF=%.4f, ContentSnippet=%s%n",
                        k + 1, doc.getId(), doc.getMetadata().get("page_number"), doc.getMetadata().get("chunk_type"),
                        prov != null ? prov.get("sources") : "N/A",
                        prov != null && prov.get("rrfScore") != null ? (Double) prov.get("rrfScore") : 0.0,
                        doc.getText().replace("\n", " ").substring(0, Math.min(70, doc.getText().length())));
            }
        }

        double hitAt1Rate = (double) hitAt1Count / questions.size() * 100.0;
        double hitAt3Rate = (double) hitAt3Count / questions.size() * 100.0;
        double hitAt5Rate = (double) hitAt5Count / questions.size() * 100.0;
        double hitAt10Rate = (double) hitAt10Count / questions.size() * 100.0;

        System.out.println("=========================================================================================");
        System.out.println("FULL EVALUATION SUMMARY (Q1 - Q11):");
        System.out.printf("  Hit@1:  %d/%d (%.1f%%)%n", hitAt1Count, questions.size(), hitAt1Rate);
        System.out.printf("  Hit@3:  %d/%d (%.1f%%)%n", hitAt3Count, questions.size(), hitAt3Rate);
        System.out.printf("  Hit@5:  %d/%d (%.1f%%)%n", hitAt5Count, questions.size(), hitAt5Rate);
        System.out.printf("  Hit@10: %d/%d (%.1f%%)%n", hitAt10Count, questions.size(), hitAt10Rate);
        System.out.printf("  Avg Retrieval Latency: %.1fms%n", (double) totalRetrievalLatencyMs / questions.size());
        System.out.printf("  Avg RAG Latency: %.1fms%n", (double) totalRagLatencyMs / questions.size());
        System.out.println("=========================================================================================");

        assertThat(hitAt5Rate).isGreaterThanOrEqualTo(90.0);
        assertThat(hitAt10Rate).isGreaterThanOrEqualTo(90.0);
    }
}
