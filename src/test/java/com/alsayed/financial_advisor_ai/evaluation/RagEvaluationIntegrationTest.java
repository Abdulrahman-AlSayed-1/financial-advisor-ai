package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.config.EvaluationConfig;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test following the Spring AI evaluation testing guide.
 * Requires Ollama (chat + embedding) and pgvector with ingested documents.
 *
 * Enable with: RUN_RAG_EVAL=true ./mvnw test -Dtest=RagEvaluationIntegrationTest
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/api/testing.html">Spring AI Evaluation Testing</a>
 */
@SpringBootTest(properties = "spring.config.name=application-test")
@EnabledIfEnvironmentVariable(named = "RUN_RAG_EVAL", matches = "true")
class RagEvaluationIntegrationTest {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private EvaluationDatasetLoader datasetLoader;

    @Test
    void evaluateRelevancyOnSingleQuestion() {
        EvaluationQuestion question = datasetLoader.loadDataset().getFirst();

        EvaluationResult result = evaluationService.evaluateQuestion(
                question, Set.of(EvaluationConfig.RELEVANCY));

        assertThat(result.getUserAnswer()).isNotBlank();
        assertThat(result.getRelevancyPass()).isNotNull();
    }

    @Test
    void evaluateAnswerCorrectnessOnSingleQuestion() {
        EvaluationQuestion question = datasetLoader.loadDataset().getFirst();

        EvaluationResult result = evaluationService.evaluateQuestion(
                question, Set.of(EvaluationConfig.ANSWER_CORRECTNESS));

        assertThat(result.getUserAnswer()).isNotBlank();
        assertThat(result.getAnswerCorrectnessPass()).isNotNull();
    }

    @Test
    void evaluateAllMetricsOnSingleQuestion() {
        EvaluationQuestion question = datasetLoader.loadDataset().getFirst();

        EvaluationResult result = evaluationService.evaluateQuestion(question);

        assertThat(result.getUserAnswer()).isNotBlank();
        assertThat(result.getRelevancyPass()).isNotNull();
        assertThat(result.getAnswerCorrectnessPass()).isNotNull();
        assertThat(result.getLatency()).isGreaterThan(0);
    }
}
