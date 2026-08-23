package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.config.EvaluationConfig;
import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs evaluation on startup when the {@code eval} profile is active.
 * Usage: ./mvnw spring-boot:run -Dspring-boot.run.profiles=eval
 */
@Component
@Profile("eval")
@RequiredArgsConstructor
public class EvaluationRunner implements CommandLineRunner {

    private final EvaluationOrchestrator evaluationOrchestrator;

    @Override
    public void run(String... args) {
        System.out.println("=== [EVAL PROFILE] Starting RAG Evaluation ===");
        List<EvaluationResult> results = evaluationOrchestrator.runEvaluations(EvaluationConfig.ALL_METRICS, 10);
        System.out.printf("=== [EVAL PROFILE] Evaluation complete. Evaluated %d questions. ===%n", results.size());
        System.out.println("Results are available in memory for further processing.");
    }
}
