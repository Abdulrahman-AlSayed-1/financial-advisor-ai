package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class RrfAblationExperimentTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private LexicalSearchService lexicalSearchService;

    @Autowired
    private StructuredCandidateRetriever structuredCandidateRetriever;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Autowired
    private MetadataReranker metadataReranker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    record QueryData(
            int id,
            int targetPage,
            String category,
            String question,
            String goldKeyword
    ) {}

    record RawQueryCandidates(
            QueryData query,
            List<Document> denseDocs,
            List<LexicalSearchService.ScoredDocument> lexDocs,
            List<StructuredCandidateRetriever.ScoredDocument> structDocs,
            Map<String, Double> rerankScores
    ) {}

    record ConfigMetrics(
            String configName,
            double r1,
            double r3,
            double r5,
            double r10,
            double mrr,
            Map<Integer, Integer> criticalQueryRanks,
            int top5DemotionsToOver10,
            int top5RrfDemotedByReranker
    ) {}

    record ScoreDecomposition(
            int queryId,
            String queryText,
            String goldChunkId,
            int denseRank,
            int lexicalRank,
            int structuredRank,
            double denseRrfContribution,
            double lexicalRrfContribution,
            double structuredRrfContribution,
            double consensusBonus,
            double totalRrfScore,
            int rrfRank,
            double rerankerScore,
            double finalScore,
            int finalRank
    ) {}

    @Test
    @DisplayName("Comprehensive RRF Fusion & Reranker Ablation Experiment on UNSEEN_V2 (N = 20)")
    void runRrfAblationExperiment() throws Exception {
        List<QueryData> queries = List.of(
                new QueryData(1, 4, "macro_narrative", "What is the team's view on U.S. economic growth and the likelihood of a recession in late 2024?", "growth"),
                new QueryData(2, 6, "macro_labor", "What was the U.S. unemployment rate reported for August 2024?", "4.2"),
                new QueryData(3, 8, "equity_earnings", "What was the year-over-year earnings growth rate for the S&P 500 in Q2 2024?", "earnings"),
                new QueryData(4, 10, "equity_valuation", "What was the trailing 12-month P/E ratio of the S&P 500 compared to its 20-year average?", "average"),
                new QueryData(5, 12, "central_banks", "What actions did the European Central Bank (ECB) take regarding policy rates in September 2024?", "rate"),
                new QueryData(6, 15, "stance_equity", "What is the team's asset allocation stance on Emerging Market Equities?", "overweight"),
                new QueryData(7, 17, "stance_fixed_income", "What is the team's asset allocation stance on Investment Grade Corporate Bonds?", "neutral"),
                new QueryData(8, 19, "stance_duration", "What is the overall portfolio duration stance recommended by the team?", "duration"),
                new QueryData(9, 23, "table_muni", "What was the yield to worst for 10-Year AAA General Obligation Municipal bonds as of September 30, 2024?", "2.8"),
                new QueryData(10, 25, "table_high_yield", "What was the option-adjusted spread (OAS) for US High Yield corporate bonds as of September 30, 2024?", "spread"),
                new QueryData(11, 27, "table_securitized", "What was the yield on 30-year fixed-rate Agency Mortgage-Backed Securities (MBS) as of September 30, 2024?", "mbs"),
                new QueryData(12, 29, "table_global_sovereign", "What was the 10-Year German Bund yield as of September 30, 2024?", "2.12"),
                new QueryData(13, 31, "table_emd", "What was the local currency sovereign debt yield for Emerging Markets as of September 30, 2024?", "local"),
                new QueryData(14, 33, "table_small_cap", "What was the 1-month and YTD return of the Russell 2000 Index through September 30, 2024?", "russell 2000"),
                new QueryData(15, 35, "table_intl_equity", "What was the NTM P/E ratio for MSCI Europe compared to MSCI USA as of September 30, 2024?", "europe"),
                new QueryData(16, 38, "table_factors", "How did the Momentum and Value factors perform on a YTD basis through September 2024?", "momentum"),
                new QueryData(17, 41, "table_sector_div", "What was the dividend yield for the S&P 500 Utilities sector as of September 30, 2024?", "utilities"),
                new QueryData(18, 43, "table_commodities", "What was the 1-year total return for Gold and WTI Crude Oil as of September 30, 2024?", "gold"),
                new QueryData(19, 46, "table_fx", "What was the exchange rate of the Japanese Yen per US Dollar as of September 30, 2024?", "yen"),
                new QueryData(20, 54, "glossary", "How does the document define the ICE BofA US High Yield Index?", "high yield")
        );

        // Step 1: Collect candidates for each query
        List<RawQueryCandidates> allQueryCandidates = new ArrayList<>();
        for (QueryData q : queries) {
            QuerySignals signals = queryAnalyzer.analyze(q.question());
            List<Document> denseDocs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(q.question()).topK(50).similarityThreshold(0.15).build());
            List<LexicalSearchService.ScoredDocument> lexDocs = lexicalSearchService.searchCorpus(q.question(), 50);
            List<StructuredCandidateRetriever.ScoredDocument> structDocs =
                    structuredCandidateRetriever.retrieveByStructuredSignals(signals, 50);

            // Collect all unique candidates for reranking
            Map<String, Document> uniqueCandidates = new LinkedHashMap<>();
            denseDocs.forEach(d -> uniqueCandidates.put(d.getId(), d));
            lexDocs.forEach(l -> uniqueCandidates.putIfAbsent(l.document().getId(), l.document()));
            structDocs.forEach(s -> uniqueCandidates.putIfAbsent(s.document().getId(), s.document()));

            List<MetadataReranker.RerankedDocument> reranked =
                    metadataReranker.rerank(q.question(), new ArrayList<>(uniqueCandidates.values()));
            Map<String, Double> rerankScores = reranked.stream()
                    .collect(Collectors.toMap(r -> r.document().getId(), MetadataReranker.RerankedDocument::rerankedScore, (a, b) -> Math.max(a, b)));

            allQueryCandidates.add(new RawQueryCandidates(q, denseDocs, lexDocs, structDocs, rerankScores));
        }

        // Define Configurations to evaluate:
        // 1. Current baseline: bonus = (Dense+Lex ? 0.020 : 0), RRF/Rerank = 35/65
        // 2. Pure RRF: bonus = 0, RRF/Rerank = 35/65
        // 3. Symmetric Fixed Bonus (0.010 for any pair, 0.020 for all 3), RRF/Rerank = 35/65
        // 4. Normalized Bonus (15% of sum of RRF scores for >=2 channels), RRF/Rerank = 35/65
        // 5. Pure RRF Only (100% RRF, no reranker)
        // 6. Symmetric RRF Only (100% RRF, no reranker)
        // 7. Pure RRF + Reranker 50/50
        // 8. Pure RRF + Reranker 65/35
        // 9. Pure RRF + Reranker 80/20
        // 10. Symmetric RRF + Reranker 50/50
        // 11. Symmetric RRF + Reranker 65/35
        // 12. Symmetric RRF + Reranker 80/20
        // 13. Normalized RRF + Reranker 65/35
        // 14. Normalized RRF + Reranker 80/20

        List<ConfigDefinition> configs = List.of(
                new ConfigDefinition("1. Current Baseline (Asymmetric +0.020, 35/65)", BonusType.ASYMMETRIC_FIXED, 0.020, 0.35, 0.65),
                new ConfigDefinition("2. Pure RRF (Bonus=0, 35/65)", BonusType.NONE, 0.0, 0.35, 0.65),
                new ConfigDefinition("3. Symmetric Fixed (+0.010, 35/65)", BonusType.SYMMETRIC_FIXED, 0.010, 0.35, 0.65),
                new ConfigDefinition("4. Symmetric Fixed (+0.020, 35/65)", BonusType.SYMMETRIC_FIXED, 0.020, 0.35, 0.65),
                new ConfigDefinition("5. Normalized Bonus (15% boost, 35/65)", BonusType.NORMALIZED_PROPORTIONAL, 0.15, 0.35, 0.65),
                new ConfigDefinition("6. Pure RRF Only (100% RRF, No Reranker)", BonusType.NONE, 0.0, 1.0, 0.0),
                new ConfigDefinition("7. Symmetric RRF Only (100% RRF, No Reranker)", BonusType.SYMMETRIC_FIXED, 0.010, 1.0, 0.0),
                new ConfigDefinition("8. Pure RRF + Reranker (50/50)", BonusType.NONE, 0.0, 0.50, 0.50),
                new ConfigDefinition("9. Pure RRF + Reranker (65/35)", BonusType.NONE, 0.0, 0.65, 0.35),
                new ConfigDefinition("10. Pure RRF + Reranker (80/20)", BonusType.NONE, 0.0, 0.80, 0.20),
                new ConfigDefinition("11. Symmetric RRF + Reranker (50/50)", BonusType.SYMMETRIC_FIXED, 0.010, 0.50, 0.50),
                new ConfigDefinition("12. Symmetric RRF + Reranker (65/35)", BonusType.SYMMETRIC_FIXED, 0.010, 0.65, 0.35),
                new ConfigDefinition("13. Symmetric RRF + Reranker (80/20)", BonusType.SYMMETRIC_FIXED, 0.010, 0.80, 0.20),
                new ConfigDefinition("14. Normalized RRF + Reranker (65/35)", BonusType.NORMALIZED_PROPORTIONAL, 0.15, 0.65, 0.35),
                new ConfigDefinition("15. Normalized RRF + Reranker (80/20)", BonusType.NORMALIZED_PROPORTIONAL, 0.15, 0.80, 0.20)
        );

        List<ConfigMetrics> allMetrics = new ArrayList<>();
        List<ScoreDecomposition> baselineDecompositions = new ArrayList<>();
        List<ScoreDecomposition> winningDecompositions = new ArrayList<>();

        for (ConfigDefinition cfg : configs) {
            ConfigMetrics m = evaluateConfig(cfg, allQueryCandidates,
                    cfg.name().startsWith("1. Current Baseline") ? baselineDecompositions :
                    cfg.name().startsWith("12. Symmetric RRF + Reranker (65/35)") ? winningDecompositions : null);
            allMetrics.add(m);
        }

        // Print Summary Table
        System.out.println("=================================================================================================================================================================");
        System.out.println("                                               RRF ABLATION EXPERIMENT RESULTS MATRIX (N = 20)");
        System.out.println("=================================================================================================================================================================");
        System.out.printf("%-45s | %-6s %-6s %-6s %-6s | %-6s | Q02  Q06  Q09  Q12  Q16  Q17  Q19 | Top5->10 DemotedByRerank%n",
                "Configuration", "R@1", "R@3", "R@5", "R@10", "MRR");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------------------------------------");

        for (ConfigMetrics m : allMetrics) {
            Map<Integer, Integer> cr = m.criticalQueryRanks();
            System.out.printf("%-45s | %-6.3f %-6.3f %-6.3f %-6.3f | %-6.3f | %-4s %-4s %-4s %-4s %-4s %-4s %-4s | %-8d %-8d%n",
                    m.configName(), m.r1(), m.r3(), m.r5(), m.r10(), m.mrr(),
                    fmtRank(cr.get(2)), fmtRank(cr.get(6)), fmtRank(cr.get(9)),
                    fmtRank(cr.get(12)), fmtRank(cr.get(16)), fmtRank(cr.get(17)), fmtRank(cr.get(19)),
                    m.top5DemotionsToOver10(), m.top5RrfDemotedByReranker());
        }
        System.out.println("=================================================================================================================================================================");

        // Print Per-Query Score Decomposition for Baseline vs Winning Configuration
        System.out.println("\n========================================================================================================================");
        System.out.println("                               BASELINE PER-QUERY SCORE DECOMPOSITION");
        System.out.println("========================================================================================================================");
        printDecompositions(baselineDecompositions);

        System.out.println("\n========================================================================================================================");
        System.out.println("                        WINNING CONFIGURATION (Symmetric RRF 65/35) PER-QUERY SCORE DECOMPOSITION");
        System.out.println("========================================================================================================================");
        printDecompositions(winningDecompositions);

        Map<String, Object> report = Map.of(
                "experiment_name", "RRF_FUSION_AND_RERANKER_ABLATION",
                "configurations", allMetrics,
                "baseline_decomposition", baselineDecompositions,
                "winning_decomposition", winningDecompositions
        );

        new File("evaluation/reports").mkdirs();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File("evaluation/reports/rrf_ablation_experiment_report.json"), report);
    }

    private void printDecompositions(List<ScoreDecomposition> list) {
        System.out.printf("%-4s %-6s %-6s %-6s | %-8s %-8s %-8s %-8s | %-8s %-6s | %-8s %-8s | %-6s%n",
                "ID", "Dense", "Lex", "Struct", "d_RRF", "l_RRF", "s_RRF", "Bonus", "tot_RRF", "rRank", "Rerank", "Final", "fRank");
        System.out.println("------------------------------------------------------------------------------------------------------------------------");
        for (ScoreDecomposition d : list) {
            System.out.printf("Q%02d %-6s %-6s %-6s | %-8.4f %-8.4f %-8.4f %-8.4f | %-8.4f %-6s | %-8.4f %-8.4f | %-6s%n",
                    d.queryId(),
                    fmtRank(d.denseRank()), fmtRank(d.lexicalRank()), fmtRank(d.structuredRank()),
                    d.denseRrfContribution(), d.lexicalRrfContribution(), d.structuredRrfContribution(), d.consensusBonus(),
                    d.totalRrfScore(), fmtRank(d.rrfRank()), d.rerankerScore(), d.finalScore(), fmtRank(d.finalRank()));
        }
    }

    private String fmtRank(Integer r) {
        if (r == null || r <= 0) return "-";
        return "#" + r;
    }

    enum BonusType {
        NONE,
        ASYMMETRIC_FIXED,
        SYMMETRIC_FIXED,
        NORMALIZED_PROPORTIONAL
    }

    record ConfigDefinition(
            String name,
            BonusType bonusType,
            double bonusParam,
            double rrfWeight,
            double rerankWeight
    ) {}

    private ConfigMetrics evaluateConfig(
            ConfigDefinition cfg,
            List<RawQueryCandidates> allQueryCandidates,
            List<ScoreDecomposition> decompCollector) {

        int hitAt1 = 0, hitAt3 = 0, hitAt5 = 0, hitAt10 = 0;
        double sumRr = 0.0;
        Map<Integer, Integer> criticalRanks = new HashMap<>();
        int top5DemotionsToOver10 = 0;
        int top5RrfDemotedByReranker = 0;

        int k = 60;

        for (RawQueryCandidates qc : allQueryCandidates) {
            QueryData q = qc.query();
            List<Document> denseDocs = qc.denseDocs();
            List<LexicalSearchService.ScoredDocument> lexDocs = qc.lexDocs();
            List<StructuredCandidateRetriever.ScoredDocument> structDocs = qc.structDocs();
            Map<String, Double> rerankScores = qc.rerankScores();

            Map<String, Document> allDocs = new LinkedHashMap<>();
            Map<String, Integer> dRanks = new HashMap<>();
            Map<String, Integer> lRanks = new HashMap<>();
            Map<String, Integer> sRanks = new HashMap<>();
            Map<String, Double> rrfContributions = new HashMap<>();
            Map<String, Double> dRrf = new HashMap<>();
            Map<String, Double> lRrf = new HashMap<>();
            Map<String, Double> sRrf = new HashMap<>();
            Map<String, Double> bonuses = new HashMap<>();
            Map<String, Set<String>> sources = new HashMap<>();

            // 1. Dense
            for (int i = 0; i < denseDocs.size(); i++) {
                String id = denseDocs.get(i).getId();
                allDocs.put(id, denseDocs.get(i));
                dRanks.put(id, i + 1);
                sources.computeIfAbsent(id, k1 -> new HashSet<>()).add("DENSE");
                double c = 1.0 / (k + i + 1);
                dRrf.put(id, c);
                rrfContributions.merge(id, c, Double::sum);
            }

            // 2. Lexical
            for (int i = 0; i < lexDocs.size(); i++) {
                String id = lexDocs.get(i).document().getId();
                allDocs.putIfAbsent(id, lexDocs.get(i).document());
                lRanks.put(id, i + 1);
                sources.computeIfAbsent(id, k1 -> new HashSet<>()).add("LEXICAL");
                double c = 1.0 / (k + i + 1);
                lRrf.put(id, c);
                rrfContributions.merge(id, c, Double::sum);
            }

            // 3. Structured
            for (int i = 0; i < structDocs.size(); i++) {
                String id = structDocs.get(i).document().getId();
                allDocs.putIfAbsent(id, structDocs.get(i).document());
                sRanks.put(id, i + 1);
                sources.computeIfAbsent(id, k1 -> new HashSet<>()).add("STRUCTURED");
                double c = 1.0 / (k + i + 1);
                sRrf.put(id, c);
                rrfContributions.merge(id, c, Double::sum);
            }

            // Apply Consensus Bonus
            for (String id : allDocs.keySet()) {
                Set<String> s = sources.getOrDefault(id, Set.of());
                double b = 0.0;
                if (cfg.bonusType() == BonusType.ASYMMETRIC_FIXED) {
                    if (s.contains("DENSE") && s.contains("LEXICAL")) {
                        b = cfg.bonusParam();
                    }
                } else if (cfg.bonusType() == BonusType.SYMMETRIC_FIXED) {
                    int channelCount = s.size();
                    if (channelCount == 2) {
                        b = cfg.bonusParam();
                    } else if (channelCount >= 3) {
                        b = cfg.bonusParam() * 2.0;
                    }
                } else if (cfg.bonusType() == BonusType.NORMALIZED_PROPORTIONAL) {
                    int channelCount = s.size();
                    if (channelCount >= 2) {
                        double sumRrf = rrfContributions.getOrDefault(id, 0.0);
                        b = sumRrf * cfg.bonusParam() * (channelCount - 1);
                    }
                }
                bonuses.put(id, b);
                rrfContributions.merge(id, b, Double::sum);
            }

            // Sort by RRF
            List<Map.Entry<String, Double>> rrfSorted = rrfContributions.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .toList();

            Map<String, Integer> rrfRanks = new HashMap<>();
            for (int i = 0; i < rrfSorted.size(); i++) {
                rrfRanks.put(rrfSorted.get(i).getKey(), i + 1);
            }

            // Combine with Reranker
            Map<String, Double> finalScores = new HashMap<>();
            for (String id : allDocs.keySet()) {
                double rrf = rrfContributions.getOrDefault(id, 0.0);
                double rerank = rerankScores.getOrDefault(id, 0.0);

                double normalizedRrf = Math.min(rrf / 0.05, 1.0);
                double normalizedRerank = Math.min(rerank / 2.0, 1.0);

                double finalScore;
                if (cfg.rerankWeight() == 0.0) {
                    finalScore = rrf;
                } else {
                    finalScore = (normalizedRrf * cfg.rrfWeight()) + (normalizedRerank * cfg.rerankWeight());
                }
                finalScores.put(id, finalScore);
            }

            List<Map.Entry<String, Double>> finalSorted = finalScores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .toList();

            // Find gold rank
            int goldFinalRank = -1;
            int goldRrfRank = -1;
            String goldDocId = "";

            for (int i = 0; i < finalSorted.size(); i++) {
                String id = finalSorted.get(i).getKey();
                Document doc = allDocs.get(id);
                if (isGold(doc, q.targetPage(), q.goldKeyword())) {
                    goldFinalRank = i + 1;
                    goldDocId = id;
                    goldRrfRank = rrfRanks.getOrDefault(id, -1);
                    break;
                }
            }

            if (goldFinalRank == 1) hitAt1++;
            if (goldFinalRank >= 1 && goldFinalRank <= 3) hitAt3++;
            if (goldFinalRank >= 1 && goldFinalRank <= 5) hitAt5++;
            if (goldFinalRank >= 1 && goldFinalRank <= 10) hitAt10++;
            if (goldFinalRank > 0) sumRr += (1.0 / goldFinalRank);

            if (Set.of(2, 6, 9, 12, 16, 17, 19).contains(q.id())) {
                criticalRanks.put(q.id(), goldFinalRank);
            }

            // Demotion metrics
            int dRank = dRanks.getOrDefault(goldDocId, -1);
            int lRank = lRanks.getOrDefault(goldDocId, -1);
            int sRank = sRanks.getOrDefault(goldDocId, -1);

            boolean inTop5Channel = (dRank >= 1 && dRank <= 5) || (lRank >= 1 && lRank <= 5) || (sRank >= 1 && sRank <= 5);
            if (inTop5Channel && (goldFinalRank > 10 || goldFinalRank == -1)) {
                top5DemotionsToOver10++;
            }

            if (goldRrfRank >= 1 && goldRrfRank <= 5 && (goldFinalRank > 5 || goldFinalRank == -1)) {
                top5RrfDemotedByReranker++;
            }

            if (decompCollector != null) {
                decompCollector.add(new ScoreDecomposition(
                        q.id(), q.question(), goldDocId,
                        dRank, lRank, sRank,
                        dRrf.getOrDefault(goldDocId, 0.0),
                        lRrf.getOrDefault(goldDocId, 0.0),
                        sRrf.getOrDefault(goldDocId, 0.0),
                        bonuses.getOrDefault(goldDocId, 0.0),
                        rrfContributions.getOrDefault(goldDocId, 0.0),
                        goldRrfRank,
                        rerankScores.getOrDefault(goldDocId, 0.0),
                        finalScores.getOrDefault(goldDocId, 0.0),
                        goldFinalRank
                ));
            }
        }

        int n = allQueryCandidates.size();
        return new ConfigMetrics(
                cfg.name(),
                (double) hitAt1 / n,
                (double) hitAt3 / n,
                (double) hitAt5 / n,
                (double) hitAt10 / n,
                sumRr / n,
                criticalRanks,
                top5DemotionsToOver10,
                top5RrfDemotedByReranker
        );
    }

    private boolean isGold(Document doc, int page, String keyword) {
        if (doc == null) return false;
        Object pObj = doc.getMetadata().get("page_number");
        int docPage = pObj instanceof Number ? ((Number) pObj).intValue() : -1;
        String text = (doc.getText() != null ? doc.getText() : "").toLowerCase();
        String sec = ((String) doc.getMetadata().getOrDefault("section", "")).toLowerCase();
        String tbl = ((String) doc.getMetadata().getOrDefault("table_name", "")).toLowerCase();

        return (docPage == page || text.contains(keyword.toLowerCase())
                || sec.contains(keyword.toLowerCase()) || tbl.contains(keyword.toLowerCase()));
    }
}
