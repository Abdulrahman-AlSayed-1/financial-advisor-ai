package com.alsayed.financial_advisor_ai.evaluation.evaluators;

import com.alsayed.financial_advisor_ai.evaluation.dto.EvaluationScore;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * LLM-based evaluators for RAG system evaluation.
 * Provides relevancy, faithfulness, context recall, and answer correctness evaluation using LLM scoring with structured JSON output.
 */
@Slf4j
@Component
public class Evaluators {

    private static final String SYSTEM_PROMPT = """
        You are an expert RAG evaluation system. Your role is to evaluate the quality of retrieved context and generated answers.
        
        IMPORTANT:
        - Never answer the user's question
        - Only evaluate the provided information
        - Return your evaluation as structured JSON
        - Be objective and precise in your scoring
        - Do NOT use Markdown code fences or any formatting
        - Return ONLY raw JSON, nothing else
        
        Your response must be valid JSON with this exact structure:
        {
            "score": <number between 0.0 and 1.0>,
            "reason": "<brief explanation of your score>"
        }
        """;

    private static final String FAITHFULNESS_PROMPT = """
        Evaluate whether the generated answer is supported by the retrieved context.

        Faithfulness measures whether the factual claims made in the generated
        answer are grounded in the retrieved context.

        IMPORTANT:
        - Evaluate ONLY whether the answer is supported by the context.
        - Do NOT compare the answer to the ground truth.
        - Do NOT penalize paraphrasing.
        - Do NOT require the context to use the same wording as the answer.
        - Structured tables, key-value pairs, metadata, abbreviations, and
          semantically equivalent terminology count as supporting evidence.
        - Numerical values count as supported when the context clearly associates
          the value with the same entity, metric, period, or condition stated
          in the answer.
        - Do not treat a claim as unsupported merely because the context expresses
          it differently.

        Evaluation procedure:

        1. Break the answer into atomic factual claims.

        2. For each claim, locate supporting evidence in the retrieved context.

        3. Determine whether the evidence actually entails the claim.

        4. Calculate:

           supported claims / total factual claims

        Example:

        Answer:
        "The current yield for U.S. Loans was 9.54%."

        Context:
        "Entity: US Loans
         Metric: Current
         Value: 9.54"

        This claim is SUPPORTED because the structured context contains the
        same entity, metric, and value even though the wording differs.

        Scoring:
        - 1.0: All factual claims are supported by the context.
        - 0.8-0.9: Nearly all claims are supported with only minor unsupported
          details.
        - 0.5-0.7: Some important claims are unsupported.
        - 0.2-0.4: Most claims lack supporting evidence.
        - 0.0-0.1: The answer is essentially unsupported by the context.

        Context:
        %s

        Answer:
        %s

        Return JSON:
        {
          "score": <number between 0.0 and 1.0>,
          "reason": "<brief explanation of supported and unsupported claims>"
        }

        Return ONLY valid JSON.
        """;

    private static final String CONTEXT_RECALL_PROMPT = """
        Evaluate whether the retrieved context contains the evidence required
        to answer the question correctly according to the ground-truth answer.

        Context recall measures RETRIEVAL COVERAGE, not answer correctness,
        textual similarity, or whether the context uses the same wording as
        the ground-truth answer.

        IMPORTANT:
        - Do NOT require the context to use the same wording as the ground truth.
        - Do NOT require an exact sentence match.
        - Structured representations, table rows, labels, metadata, abbreviations,
          and equivalent terminology count as evidence.
        - A value in the context counts as recalled if it can be correctly
          associated with the entity, metric, time period, or condition requested
          by the question.
        - Do NOT penalize the context because the generated answer is wrong.
        - Do NOT evaluate the quality of the generated answer.
        - Do NOT evaluate whether the retrieved context is topically relevant.
        - Do NOT require the context to contain unnecessary information that is
          not needed to answer the question.

        Evaluation procedure:

        1. Identify the minimum set of factual evidence required to answer the
           question correctly according to the ground-truth answer.

        2. For each required fact, search the retrieved context for direct or
           semantically equivalent evidence.

        3. Treat the following as equivalent evidence when the meaning is clear:
           - synonyms and abbreviations
           - table/entity labels
           - structured key-value representations
           - different grammatical formulations
           - numerical values with their associated entity/metric/period
           - implicit relationships that are unambiguous from a table structure

        4. Determine how many required facts are supported by the retrieved
           context.

        5. Calculate context recall as:

           supported required facts / total required facts

        Example:

        Question:
        What was the current yield for U.S. Loans as of September 30, 2024?

        Ground Truth:
        The current yield for U.S. Loans was 9.54%.

        Retrieved Context:
        Entity: US Loans
        Metric: Current
        Value: 9.54
        Source date: 9/30/24

        This MUST receive a high recall score because the context contains
        the required evidence even though it does not use the exact phrase
        "current yield".

        Scoring:
        - 1.0: All required evidence is present in the context.
        - 0.8-0.9: Nearly all required evidence is present; only minor
          non-essential details are missing.
        - 0.5-0.7: Some required evidence is present, but an important part
          needed to answer correctly is missing.
        - 0.2-0.4: Only limited evidence is present and the key answer cannot
          be reliably derived.
        - 0.0-0.1: The essential evidence required to answer the question
          is absent.

        Question:
        %s

        Ground Truth Answer:
        %s

        Retrieved Context:
        %s

        Return JSON with:
        {
          "score": <number between 0.0 and 1.0>,
          "reason": "<brief explanation identifying the required evidence and
                     whether each piece was found in the retrieved context>"
        }

        Return ONLY valid JSON.
        """;

    private static final String ANSWER_CORRECTNESS_PROMPT = """
        Evaluate the answer correctness by comparing the generated answer to the ground-truth answer.
        
        Answer correctness measures whether the generated answer matches the ground-truth answer in terms of factual accuracy and completeness.
        
        Step 1: Extract key facts from both answers
        Step 2: Compare each fact for accuracy
        Step 3: Calculate correctness as (matching facts / total facts in ground-truth)
        
        Scoring criteria (strict):
        - 1.0: Generated answer is completely correct and matches ground-truth in all key facts
        - 0.8-0.9: Answer is mostly correct with 1-2 minor inaccuracies or missing details
        - 0.5-0.7: Answer contains significant factual errors or is missing key information
        - 0.2-0.4: Answer is partially correct but has major errors
        - 0.0: Answer is incorrect or contradicts the ground-truth
        
        Question:
        %s
        
        Ground Truth Answer:
        %s
        
        Generated Answer:
        %s
        
        Return JSON with score and reasoning, listing specific facts that match vs differ.
        """;

    private static final String RELEVANCY_PROMPT = """
        Evaluate how relevant the retrieved context is to the user's question.

        Relevancy measures the degree to which the retrieved context is
        topically and semantically related to the question and useful for
        addressing it.

        IMPORTANT:
        Relevancy is NOT a completeness metric.
        Do not penalize the context simply because it does not contain every
        piece of information required to answer the question.
        Do not determine whether the entire answer can be produced from the context.
        That is evaluated separately by Context Recall.

        Evaluation steps:

        1. Identify the main subject, entities, concepts, and requested information
           in the question.

        2. Determine whether the retrieved context discusses the same subject,
           entities, concepts, or requested information.

        3. Evaluate how directly the context relates to the question.
           Prefer context that directly addresses the specific entity, metric,
           event, relationship, or concept being asked about.

        4. Ignore unrelated information even if it comes from the same document
           or financial domain.

        Scoring criteria:

        - 1.0: Context is directly and strongly relevant to the question.
               It discusses the exact subject/entity/concept being asked about
               and is clearly useful for answering the question.

        - 0.8-0.9: Context is highly relevant and closely related to the question,
                   but may focus on a closely related aspect rather than the exact
                   requested information.

        - 0.5-0.7: Context is moderately relevant.
                   It concerns the same broader topic but is only partially useful
                   for the specific question.

        - 0.2-0.4: Context is weakly relevant or mostly provides generic/background
                   information related to the broader topic.

        - 0.0: Context is unrelated to the question or provides no useful
               information for addressing it.

        Do NOT reduce the relevancy score merely because:
        - the answer is incomplete,
        - a required value is missing,
        - some required evidence is absent,
        - another chunk may be needed to answer the question completely.

        Those are retrieval coverage/context recall concerns, not relevancy.

        Question:
        %s

        Retrieved Context:
        %s

        Return JSON with:
        - score
        - reason

        Do not evaluate the generated answer.
        """;

    private static final double DEFAULT_SCORE = 0.5;
    private static final String DEFAULT_REASONING = "Evaluation failed - using default score";

    // Dedicated ChatClient without RAG advisors to prevent evaluation prompts from triggering retrieval cycles
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public Evaluators(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate faithfulness - measures if the answer is grounded in the retrieved context.
     * Uses LLM to check if the answer can be derived from the context.
     *
     * @param answer The RAG system answer
     * @param contexts The retrieved contexts
     * @return EvaluationScore with score and reasoning
     */
    public EvaluationScore evaluateFaithfulness(String answer, List<String> contexts) {
        String contextText = String.join("\n\n", contexts);
        // Build prompt manually to avoid String.format() issues with % characters in input text
        String userPrompt = FAITHFULNESS_PROMPT;
        int firstIndex = userPrompt.indexOf("%s");
        int secondIndex = userPrompt.indexOf("%s", firstIndex + 2);
        
        StringBuilder sb = new StringBuilder();
        sb.append(userPrompt.substring(0, firstIndex));
        sb.append(contextText);
        sb.append(userPrompt.substring(firstIndex + 2, secondIndex));
        sb.append(answer);
        sb.append(userPrompt.substring(secondIndex + 2));
        
        return evaluateWithRetry("faithfulness", sb.toString());
    }

    /**
     * Evaluate context recall - measures if the retrieved context contains information needed to answer the question.
     * This aligns with the academic definition used by RAGAS.
     *
     * @param question The original question
     * @param groundTruth The ground-truth answer
     * @param contexts The retrieved contexts
     * @return EvaluationScore with score and reasoning
     */
    public EvaluationScore evaluateContextRecall(String question, String groundTruth, List<String> contexts) {
        String contextText = String.join("\n\n", contexts);
        // Build prompt manually to avoid String.format() issues with % characters in input text
        String userPrompt = CONTEXT_RECALL_PROMPT;
        int firstIndex = userPrompt.indexOf("%s");
        int secondIndex = userPrompt.indexOf("%s", firstIndex + 2);
        int thirdIndex = userPrompt.indexOf("%s", secondIndex + 2);
        
        StringBuilder sb = new StringBuilder();
        sb.append(userPrompt.substring(0, firstIndex));
        sb.append(question);
        sb.append(userPrompt.substring(firstIndex + 2, secondIndex));
        sb.append(groundTruth);
        sb.append(userPrompt.substring(secondIndex + 2, thirdIndex));
        sb.append(contextText);
        sb.append(userPrompt.substring(thirdIndex + 2));
        
        return evaluateWithRetry("contextRecall", sb.toString());
    }

    /**
     * Evaluate answer correctness - measures if the generated answer matches the ground-truth answer.
     * Compares factual accuracy and completeness between generated and ground-truth answers.
     *
     * @param question The original question
     * @param groundTruth The ground-truth answer
     * @param generatedAnswer The RAG system generated answer
     * @return EvaluationScore with score and reasoning
     */
    public EvaluationScore evaluateAnswerCorrectness(String question, String groundTruth, String generatedAnswer) {
        // Build prompt manually to avoid String.format() issues with % characters in input text
        String userPrompt = ANSWER_CORRECTNESS_PROMPT;
        int firstIndex = userPrompt.indexOf("%s");
        int secondIndex = userPrompt.indexOf("%s", firstIndex + 2);
        int thirdIndex = userPrompt.indexOf("%s", secondIndex + 2);
        
        StringBuilder sb = new StringBuilder();
        sb.append(userPrompt.substring(0, firstIndex));
        sb.append(question);
        sb.append(userPrompt.substring(firstIndex + 2, secondIndex));
        sb.append(groundTruth);
        sb.append(userPrompt.substring(secondIndex + 2, thirdIndex));
        sb.append(generatedAnswer);
        sb.append(userPrompt.substring(thirdIndex + 2));
        
        return evaluateWithRetry("answerCorrectness", sb.toString());
    }

    /**
     * Evaluate relevancy - measures whether the retrieved context contains information to answer the question.
     * Custom implementation to replace Spring AI's RelevancyEvaluator which may have compatibility issues.
     *
     * @param question The original question
     * @param contexts The retrieved contexts
     * @return EvaluationScore with score and reasoning
     */
    public EvaluationScore evaluateRelevancy(String question, List<String> contexts) {
        String contextText = String.join("\n\n", contexts);
        // Build prompt manually to avoid String.format() issues with % characters in input text
        String userPrompt = RELEVANCY_PROMPT;
        int firstIndex = userPrompt.indexOf("%s");
        int secondIndex = userPrompt.indexOf("%s", firstIndex + 2);
        
        StringBuilder sb = new StringBuilder();
        sb.append(userPrompt.substring(0, firstIndex));
        sb.append(question);
        sb.append(userPrompt.substring(firstIndex + 2, secondIndex));
        sb.append(contextText);
        sb.append(userPrompt.substring(secondIndex + 2));
        
        return evaluateWithRetry("relevancy", sb.toString());
    }

    /**
     * Common evaluation logic with structured JSON parsing and error handling.
     */
    private EvaluationScore evaluateWithRetry(String metricName, String userPrompt) {
        long startTime = System.currentTimeMillis();

        try {
            String response = callEvaluationLLM(userPrompt);
            EvaluationScore score = parseEvaluationResponse(response);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Evaluation completed - metric: {}, score: {}, reasoning: {}, elapsed: {}ms",
                metricName, score.score(), score.reasoning(), elapsed);

            return score;

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("JSON parsing failed for metric {} after {}ms: {}", metricName, elapsed, e.getMessage());
            return new EvaluationScore(DEFAULT_SCORE, "JSON parsing error: " + e.getMessage());

        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("LLM call failed for metric {} after {}ms: {}", metricName, elapsed, e.getMessage());
            return new EvaluationScore(DEFAULT_SCORE, "LLM call error: " + e.getMessage());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Unexpected error evaluating metric {} after {}ms: {}", metricName, elapsed, e.getMessage());
            return new EvaluationScore(DEFAULT_SCORE, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Call the LLM with system prompt and user prompt.
     */
    private String callEvaluationLLM(String userPrompt) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * Parse structured JSON response from LLM.
     * Handles JSON wrapped in Markdown code fences.
     */
    @SuppressWarnings("unchecked")
    private EvaluationScore parseEvaluationResponse(String response) throws IOException {
        String cleanedResponse = extractJsonFromResponse(response);
        Map<String, Object> jsonMap = objectMapper.readValue(cleanedResponse, Map.class);

        double score = extractDoubleFromMap(jsonMap, "score", DEFAULT_SCORE);
        String reason = extractStringFromMap(jsonMap, "reason", DEFAULT_REASONING);

        // Clamp score to valid range
        score = Math.max(0.0, Math.min(1.0, score));

        return new EvaluationScore(score, reason);
    }

    /**
     * Extract JSON from LLM response, handling Markdown code fences.
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.isBlank()) {
            return response;
        }

        String trimmed = response.trim();

        // Remove Markdown code fences if present
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7).trim();
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3).trim();
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        return trimmed;
    }

    private double extractDoubleFromMap(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String extractStringFromMap(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
