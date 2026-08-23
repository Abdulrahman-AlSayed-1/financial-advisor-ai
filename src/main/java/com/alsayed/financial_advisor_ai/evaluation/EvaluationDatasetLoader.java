package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationQuestion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;

@Component
public class EvaluationDatasetLoader {

    /**
     * Path is relative to the project root — evaluation/datasets/evaluation_dataset.json.
     * This file lives OUTSIDE src/ intentionally: it is evaluation data, not source code.
     * Spring Boot / Maven always run from the project root, so this resolves correctly.
     */
    private static final String DATASET_PATH = "evaluation/datasets/evaluation_dataset.json";

    private final ObjectMapper objectMapper;

    public EvaluationDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvaluationQuestion> loadDataset() {
        File file = new File(DATASET_PATH);
        if (!file.exists()) {
            throw new RuntimeException(
                "Evaluation dataset not found at: " + file.getAbsolutePath() +
                "\nExpected location: <project-root>/evaluation/datasets/evaluation_dataset.json"
            );
        }
        List<EvaluationQuestion> questions = objectMapper.readValue(file,
            objectMapper.getTypeFactory().constructCollectionType(List.class, EvaluationQuestion.class));
        System.out.println("Loaded " + questions.size() + " questions from " + file.getAbsolutePath());
        return questions;
    }
}
