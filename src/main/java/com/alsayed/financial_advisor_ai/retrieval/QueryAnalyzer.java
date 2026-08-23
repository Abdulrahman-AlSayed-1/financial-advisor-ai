package com.alsayed.financial_advisor_ai.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

/**
 * Analyzes queries to extract structured signals for comparative retrieval.
 * 
 * This component detects comparative/aggregation queries and extracts:
 * - Entities (sectors, asset classes, etc.)
 * - Metrics (return, yield, spread, etc.)
 * - Periods (YTD, 1Y, etc.)
 * - Comparison operations (highest, lowest, top N, etc.)
 * - Result limits (for top/bottom N queries)
 */
@Slf4j
@Component
public class QueryAnalyzer {

    private final EntityRegistry entityRegistry;
    private final MetricResolver metricResolver;

    public QueryAnalyzer(EntityRegistry entityRegistry, MetricResolver metricResolver) {
        this.entityRegistry = entityRegistry;
        this.metricResolver = metricResolver;
    }

    // Comparison operation patterns
    private static final Pattern HIGHEST_PATTERN = Pattern.compile(
        "\\b(highest|largest|maximum|max|greatest|best)\\b", Pattern.CASE_INSENSITIVE);
    
    private static final Pattern LOWEST_PATTERN = Pattern.compile(
        "\\b(lowest|smallest|minimum|min|least|worst)\\b", Pattern.CASE_INSENSITIVE);
    
    private static final Pattern TOP_N_PATTERN = Pattern.compile(
        "\\btop\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    
    private static final Pattern BOTTOM_N_PATTERN = Pattern.compile(
        "\\b(bottom\\s+\\d+|lowest\\s+\\d+|worst\\s+\\d+)\\b", Pattern.CASE_INSENSITIVE);
    
    // Numeric limit patterns
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");
    
    // Period patterns
    private static final Set<String> PERIODS = Set.of(
        "ytd", "1-year", "1-yr", "1 yr", "1 year", "1y", "3-year", "3-yr", "3 yr", "3 year", "3y",
        "5-year", "5-yr", "5 yr", "5 year", "5y", "10-year", "10-yr", "10 yr", "10 year", "10y",
        "current", "12-mo", "12-month", "12 month", "1-mo", "1-month", "1 month", "3-mo", "3-month", "3 month",
        "average", "median"
    );
    
    // Semantic Metric Categories removed in favor of CanonicalMetric ontology + MetricResolver
    
    // Table type patterns
    private static final Set<String> TABLE_TYPES = Set.of(
        "sector", "asset class", "correlation", "bond", "loan", "central bank policy rates", "fund flows"
    );

    // Tenor / Maturity patterns
    private static final Pattern TENOR_PATTERN = Pattern.compile(
        "\\b(\\d+)\\s*(?:-|\\s*)?(yr|year|years|mo|month|months|y|m)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Extract canonical structured tenors/maturities from text (e.g. "10-year" -> "10y", "3-mo" -> "3m").
     */
    public static Set<String> extractTenors(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> tenors = new LinkedHashSet<>();
        Matcher matcher = TENOR_PATTERN.matcher(text);
        while (matcher.find()) {
            String num = matcher.group(1);
            String unit = matcher.group(2).toLowerCase();
            if (unit.startsWith("y")) {
                tenors.add(num + "y");
            } else if (unit.startsWith("m")) {
                tenors.add(num + "m");
            }
        }
        return tenors;
    }

    /**
     * Extract unit of measurement from text (e.g. "basis points", "bps", "%", "percent", "percentage").
     */
    public static String extractUnit(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase();
        if (lower.contains("basis points") || lower.contains("bps") || lower.contains("basis point")) {
            return "bps";
        }
        if (lower.contains("%") || lower.contains("percent") || lower.contains("percentage")) {
            return "%";
        }
        return null;
    }
    
    /**
     * Analyze a query and extract structured signals.
     */
    public QuerySignals analyze(String query) {
        QuerySignals signals = new QuerySignals();
        signals.setRawQuery(query);
        String lowerQuery = query.toLowerCase();
        
        // Extract entities
        signals.setEntities(extractEntities(query));
        
        // Extract metric
        List<MetricResolution> metricResolutions = metricResolver.resolve(lowerQuery);
        
        Set<CanonicalMetric> detectedMetrics = new LinkedHashSet<>();
        for (MetricResolution res : metricResolutions) {
            detectedMetrics.add(res.canonicalMetric());
        }
        signals.setCanonicalMetrics(detectedMetrics);
        
        if (!metricResolutions.isEmpty()) {
            CanonicalMetric primaryMetric = metricResolutions.get(0).canonicalMetric();
            signals.setCanonicalMetric(primaryMetric);
            if (primaryMetric.getSearchAliases() != null && !primaryMetric.getSearchAliases().isEmpty()) {
                signals.setMetric(primaryMetric.getSearchAliases().getFirst());
            }
            if (primaryMetric.getUnderlyingEvidenceMetrics() != null && !primaryMetric.getUnderlyingEvidenceMetrics().isEmpty()) {
                signals.setDerivedEvidenceMetrics(primaryMetric.getUnderlyingEvidenceMetrics());
            }
        }

        // Extract unit
        signals.setCanonicalUnit(extractUnit(lowerQuery));

        // Extract table types
        Set<String> tableTypes = extractTableTypes(lowerQuery);
        if (signals.getCanonicalMetric() == CanonicalMetric.POLICY_RATE) {
            tableTypes.add("central bank policy rates");
        } else if (signals.getCanonicalMetric() == CanonicalMetric.INFLOW || signals.getCanonicalMetric() == CanonicalMetric.OUTFLOW || signals.getCanonicalMetric() == CanonicalMetric.FLOW) {
            tableTypes.add("fund flows");
        } else if (signals.getCanonicalMetric() == CanonicalMetric.SECTOR_WEIGHT) {
            tableTypes.add("sector");
        }
        signals.setTableTypes(tableTypes);
        
        // Extract period
        signals.setPeriod(extractPeriod(lowerQuery));
        
        // Extract tenors
        signals.setTenors(extractTenors(query));
        
        // Extract date constraint
        signals.setDateConstraint(extractDateConstraint(query));
        
        // Determine comparison operation
        signals.setOperation(determineComparisonOperation(lowerQuery));
        
        // Extract limit
        signals.setLimit(extractLimit(lowerQuery, signals.getOperation()));
        
        // Determine if comparative
        signals.setComparative(signals.getOperation() != QuerySignals.ComparisonOperation.NONE);
        
        // Extract grouping
        signals.setGroupBy(extractGroupBy(query, signals.getEntities()));
        
        // Determine generalized query intent
        signals.setQueryIntent(determineQueryIntent(query, lowerQuery, signals));
        
        log.debug("Analyzed query: '{}', signals: {}", query, signals);
        
        return signals;
    }
    
    /**
     * Determine generalized domain-independent query intent based on linguistic and semantic structure.
     */
    public QueryIntent determineQueryIntent(String query, String lowerQuery, QuerySignals signals) {
        // 1. Comparative queries (highest, lowest, top N, comparative ranking)
        if (signals.isComparative() || signals.getOperation() != QuerySignals.ComparisonOperation.NONE) {
            return QueryIntent.COMPARATIVE_NUMERICAL;
        }

        // 2. Declarative / Direct Excerpt / Quotation queries
        if (query.contains("\"") || (query.length() > 60 && !lowerQuery.contains("what") && !lowerQuery.contains("which") && !lowerQuery.contains("how") && !lowerQuery.contains("why") && (query.contains(".") || query.contains(",")))) {
            return QueryIntent.DECLARATIVE_QUOTATION;
        }

        // 3. Causal explanation / Why queries
        if (lowerQuery.matches("(?i)^\\s*(why|what caused|what is the reason|explain why|what drove|driver of)\\b.*")
                || lowerQuery.contains("why did ") || lowerQuery.contains("why is ") || lowerQuery.contains("why are ")) {
            return QueryIntent.EXPLANATION_WHY;
        }

        // 4. Narrative / Qualitative View queries (prioritize stance/view/outlook/positioning over broad topic words like duration/credit)
        boolean hasNarrativeKeywords = lowerQuery.matches("(?i).*\\b(view|views|stance|outlook|perspective|opinion|commentary|positioning|strategy|sentiment|thesis|tactical view|tactical stance)\\b.*");
        boolean hasStrictNumericMetric = signals.getCanonicalMetric() == CanonicalMetric.VALUATION_PE 
                || signals.getCanonicalMetric() == CanonicalMetric.DIVIDEND_YIELD
                || signals.getCanonicalMetric() == CanonicalMetric.FLOW 
                || signals.getCanonicalMetric() == CanonicalMetric.DEFAULT_RATE
                || (signals.getCanonicalMetric() == CanonicalMetric.TOTAL_RETURN && (signals.getPeriod() != null || !signals.getTenors().isEmpty()));

        if (hasNarrativeKeywords && !hasStrictNumericMetric) {
            return QueryIntent.NARRATIVE_VIEW;
        }

        // 5. Numerical Lookup queries
        if (signals.getCanonicalMetric() != null || signals.getMetric() != null || !signals.getTenors().isEmpty()
                || lowerQuery.matches("(?i).*\\b(yield|return|spread|rate|ratio|multiple|valuation|price|level|flows?|bps|basis points|percent|%)\\b.*")) {
            return QueryIntent.NUMERICAL_LOOKUP;
        }

        // 6. Narrative fallback if keywords present
        if (hasNarrativeKeywords) {
            return QueryIntent.NARRATIVE_VIEW;
        }

        return QueryIntent.GENERAL_DOCUMENT_QUERY;
    }
    
    /**
     * Extract entities from query using the dynamic entity registry.
     * This replaces hardcoded entity lists with dynamic extraction from document metadata.
     */
    private Set<String> extractEntities(String query) {
        Set<String> entities = new HashSet<>();
        
        // Use dynamic entity registry for matching
        Set<String> matchedEntities = entityRegistry.extractEntitiesFromQuery(query);
        entities.addAll(matchedEntities);
        
        // Fallback to capitalized phrase extraction for entities not in registry
        Set<String> interrogatives = Set.of(
            "which", "what", "where", "when", "why", "who", "whom", "whose", "how",
            "identify", "find", "list", "name", "show", "tell"
        );
        Set<String> commonWords = Set.of("Which", "What", "Where", "When", "Why", "How", "September", "January", 
            "February", "March", "April", "May", "June", "July", "August", "October", "November", "December");
        
        Pattern entityPattern = Pattern.compile("\\b([A-Z][a-zA-Z&]+(?:\\s+[A-Z][a-zA-Z&]+)*)\\b");
        Matcher matcher = entityPattern.matcher(query);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            // Split into tokens to remove leading interrogative words
            String[] tokens = candidate.split("\\s+");
            List<String> cleanTokens = new ArrayList<>();
            for (String t : tokens) {
                String tLower = t.toLowerCase();
                if (!interrogatives.contains(tLower) && !commonWords.contains(t) && !t.equalsIgnoreCase("YTD") && !t.equalsIgnoreCase("US")) {
                    cleanTokens.add(t);
                }
            }
            if (!cleanTokens.isEmpty()) {
                String cleanCandidate = String.join(" ", cleanTokens).toLowerCase().trim();
                if (cleanCandidate.length() > 2) {
                    entities.add(cleanCandidate);
                }
            }
        }

        // Filter out false positive metric/period/stopword entities
        Set<String> metricStopwords = Set.of(
            "yield to worst", "yield-to-worst", "to worst", "worst", "yield",
            "total return", "year total return", "1-year total return", "return", "returns",
            "price", "coupon", "duration", "maturity", "bps", "basis points", "spread", "rate", "rates",
            "weight", "weights", "weighting", "allocation",
            "month", "months", "year", "years", "mo", "yr", "period", "day", "days", "quarter", "quarters"
        );
        entities.removeIf(e -> metricStopwords.contains(e) || e.matches("^\\d{4}$") || e.startsWith("which ") || e.startsWith("what "));
        
        log.debug("Extracted entities from query: {}", entities);
        return entities;
    }
    
    /**
     * Extract table types from query.
     */
    private Set<String> extractTableTypes(String query) {
        Set<String> tableTypes = new HashSet<>();
        
        for (String tableType : TABLE_TYPES) {
            if (query.contains(tableType)) {
                tableTypes.add(tableType);
            }
        }

        // Generalized Central-Bank Policy Rate and Instrument Terminology Normalization
        if (query.matches("(?i).*\\b(target rate|target range|policy rates?|central bank rates?|central bank policy rates?|fed funds|federal funds)\\b.*")) {
            tableTypes.add("central bank policy rates");
        }
        
        return tableTypes;
    }
    

    
    /**
     * Extract period from query.
     */
    private String extractPeriod(String query) {
        for (String period : PERIODS) {
            if (query.contains(period)) {
                return period;
            }
        }
        return null;
    }
    
    /**
     * Extract date constraint from query.
     */
    private String extractDateConstraint(String query) {
        // Match date patterns like "September 30, 2024" or "2024-09-30"
        Pattern datePattern = Pattern.compile(
            "(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}|\\d{4}-\\d{2}-\\d{2}");
        Matcher matcher = datePattern.matcher(query);
        
        if (matcher.find()) {
            return matcher.group();
        }
        
        return null;
    }
    
    /**
     * Determine the comparison operation type.
     */
    private QuerySignals.ComparisonOperation determineComparisonOperation(String query) {
        // Clean out standard non-comparative financial metric phrases
        String cleanedQuery = query.replaceAll("(?i)\\b(yield\\s+to\\s+worst|yield-to-worst|worst\\s+case)\\b", " ");

        // Check for top N pattern first (more specific)
        Matcher topNMatcher = TOP_N_PATTERN.matcher(cleanedQuery);
        if (topNMatcher.find()) {
            return QuerySignals.ComparisonOperation.TOP_N;
        }
        
        // Check for bottom N pattern
        Matcher bottomNMatcher = BOTTOM_N_PATTERN.matcher(cleanedQuery);
        if (bottomNMatcher.find()) {
            return QuerySignals.ComparisonOperation.BOTTOM_N;
        }
        
        // Check for highest/largest/maximum
        Matcher highestMatcher = HIGHEST_PATTERN.matcher(cleanedQuery);
        if (highestMatcher.find()) {
            return QuerySignals.ComparisonOperation.MAXIMUM;
        }
        
        // Check for lowest/smallest/minimum
        Matcher lowestMatcher = LOWEST_PATTERN.matcher(cleanedQuery);
        if (lowestMatcher.find()) {
            return QuerySignals.ComparisonOperation.MINIMUM;
        }
        
        return QuerySignals.ComparisonOperation.NONE;
    }
    
    /**
     * Extract limit (N) from query.
     */
    private Integer extractLimit(String query, QuerySignals.ComparisonOperation operation) {
        // For top N pattern, extract the number
        Matcher topNMatcher = TOP_N_PATTERN.matcher(query);
        if (topNMatcher.find()) {
            try {
                return Integer.parseInt(topNMatcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse limit from top N pattern");
            }
        }
        
        // For bottom N pattern, extract the number
        Matcher bottomNMatcher = BOTTOM_N_PATTERN.matcher(query);
        if (bottomNMatcher.find()) {
            try {
                String match = bottomNMatcher.group();
                Matcher numMatcher = NUMBER_PATTERN.matcher(match);
                if (numMatcher.find()) {
                    return Integer.parseInt(numMatcher.group(1));
                }
            } catch (NumberFormatException e) {
                log.warn("Failed to parse limit from bottom N pattern");
            }
        }
        
        // For "two", "three", etc., convert to numbers
        Map<String, Integer> wordNumbers = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10
        );
        
        for (Map.Entry<String, Integer> entry : wordNumbers.entrySet()) {
            if (query.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        // Default limit for MAXIMUM/MINIMUM operations
        if (operation == QuerySignals.ComparisonOperation.MAXIMUM || 
            operation == QuerySignals.ComparisonOperation.MINIMUM) {
            return 1;
        }
        
        return null;
    }
    
    /**
     * Extract grouping field from query.
     */
    private String extractGroupBy(String query, Set<String> entities) {
        // If query mentions "sector", group by sector
        if (query.toLowerCase().contains("sector")) {
            return "sector";
        }
        
        // If query mentions "asset class", group by asset class
        if (query.toLowerCase().contains("asset class")) {
            return "asset_class";
        }
        
        // If query mentions "country", group by country
        if (query.toLowerCase().contains("country")) {
            return "country";
        }
        
        // Default: infer from entities
        if (!entities.isEmpty()) {
            return "entity";
        }
        
        return null;
    }
}
