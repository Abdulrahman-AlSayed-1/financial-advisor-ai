package com.alsayed.financial_advisor_ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class IngestionService implements CommandLineRunner {

    private final VectorStore vectorStore;
    @Value("classpath:/docs/article_thebeatoct2024.pdf")
    private Resource marketPDF;


    @Override
    public void run(String... args) throws Exception {
        var pdfReader = new ParagraphPdfDocumentReader(marketPDF);
        TextSplitter textSplitter = TokenTextSplitter.builder().build();
        vectorStore.accept(textSplitter.apply(pdfReader.get()));
        log.info("Vector Store Loaded with Data!");
    }
}
