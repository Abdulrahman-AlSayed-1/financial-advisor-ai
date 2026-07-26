package com.alsayed.financial_advisor_ai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatClient chatClient;

   public ChatController(ChatClient.Builder chatClient , PgVectorStore vectorStore) {
        this.chatClient = chatClient
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String prompt){
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

}
