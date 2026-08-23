package com.alsayed.financial_advisor_ai.controller;

import com.alsayed.financial_advisor_ai.retrieval.HybridRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final HybridRetrievalService hybridRetrievalService;
    private final ChatClient.Builder chatClientBuilder;

    @org.springframework.beans.factory.annotation.Value("${app.rag.search.top-k:10}")
    private int topK;

    @org.springframework.beans.factory.annotation.Value("${app.rag.active-reporting-date:September 30, 2024}")
    private String activeReportingDate;

    private static final String RAG_PROMPT_TEMPLATE = """
            You are a precise financial assistant. Answer the user prompt using ONLY the provided context.
            Be concise, factual, and direct.

            Guidelines:
            1. Temporal Anchor: The active reporting date for the provided context is %s. Treat generic temporal columns (like 'YTD', 'Current') as corresponding strictly to this active reporting date. Any request for a date outside this active reporting date must not be mapped to these generic columns.
            2. High Precision Synthesis: Answer the user's prompt clearly, directly, and comprehensively using only facts from the context.
            3. Financial Table Conventions & Shorthand:
               - '1Y' or '1-Yr' corresponds to 1-Year Total Return.
               - 'YTD' corresponds to Year-to-Date Return.
               - '1-Mo.' and '3-Mo.' correspond to 1-Month and 3-Month Returns.
               - 'Current' corresponds to the active reporting/as-of date value.
               - CRITICAL: Never substitute '1Y' metrics when 'YTD' is explicitly requested.
            4. Column Isolation & Numerical Comparisons (Highest / Lowest):
               - When evaluating tables with multiple columns (Current, High, Low, Average), look ONLY at the 'Current' column for active date metrics.
               - CRITICAL: When answering "highest", "lowest", or "top" queries, you MUST begin your response with a <scratchpad> block where you extract and list EVERY single matching entity and its value from ALL chunks. Then close the </scratchpad> and provide the final sorted answer.
               - State both the exact entity name and its corresponding value. Ensure the entity matches the exact classification requested (e.g. S&P 500 vs Russell).
            5. Qualitative and Positioning Fidelity: When asked about qualitative views, tactical outlooks, or allocation stances (e.g. overweight, underweight, neutral, attractive), synthesize all stated rationale, catalysts, risks, and regional/asset-class details.
            6. Absolute Truthfulness: Base all factual assertions on the context. If the context contains NO relevant evidence at all for the prompt, state that the information is not provided in the given context. CRITICAL: If a chunk provides a table summary (listing columns and entities) but lacks the actual numerical rows, DO NOT hallucinate or invent the missing data. Explicitly state that the data is missing from the retrieved context.

            Context:
            %s

            User Prompt:
            %s
            """;

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt) {
        log.info("Production /chat request: '{}'", prompt);

        List<Document> retrievedDocs = hybridRetrievalService.search(prompt, topK);

        List<String> contexts = retrievedDocs.stream().map(Document::getText).toList();
        String rawContextText = String.join("\n\n", contexts);
        
        // Inject the reporting date directly into the context payload to satisfy strict grounding rules for smaller LLMs
        String contextText = String.format("--- GLOBAL CONTEXT METADATA ---\nActive Reporting Date: %s\n-------------------------------\n\n%s", 
                activeReportingDate, rawContextText);

        String userPrompt = String.format(RAG_PROMPT_TEMPLATE, activeReportingDate, contextText, prompt);

        String response = chatClientBuilder.build()
                .prompt()
                .user(userPrompt)
                .call()
                .content();

        // Production Forensic Diagnostic Classification
        classifyResponseForensics(prompt, contextText, response);

        return response;
    }

    private void classifyResponseForensics(String prompt, String contextText, String response) {
        if (response == null) return;
        String respLower = response.toLowerCase();
        boolean claimsNotProvided = respLower.contains("not provided")
                || respLower.contains("cannot provide")
                || respLower.contains("not mentioned")
                || respLower.contains("does not provide")
                || respLower.contains("no information");

        boolean contextHasKeywords = false;
        String[] promptTokens = prompt.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        int matchCount = 0;
        for (String t : promptTokens) {
            if (t.length() > 3 && contextText.toLowerCase().contains(t)) {
                matchCount++;
            }
        }
        if (matchCount >= 2) {
            contextHasKeywords = true;
        }

        if (claimsNotProvided && contextHasKeywords) {
            if (respLower.contains("comprehensive") || respLower.contains("historical") || respLower.contains("beyond")) {
                log.warn("[FORENSIC TRACE: PROMPT_SCOPE_EXPANSION] Model received relevant context but refused due to inferred broader scope. Prompt: '{}'", prompt);
            } else {
                log.warn("[FORENSIC TRACE: ANSWERABILITY_MISCLASSIFICATION] Context contains keywords for prompt but model abstained. Prompt: '{}'", prompt);
            }
        } else if (claimsNotProvided) {
            log.info("[FORENSIC TRACE: CORRECT_ABSTENTION] Context lacked evidence and model properly abstained. Prompt: '{}'", prompt);
        } else {
            log.info("[FORENSIC TRACE: CORRECT_ANSWER] Response generated from retrieved context. Prompt: '{}'", prompt);
        }
    }
}

