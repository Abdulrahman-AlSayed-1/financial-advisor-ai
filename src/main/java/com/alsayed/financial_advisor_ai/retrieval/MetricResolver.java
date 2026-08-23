package com.alsayed.financial_advisor_ai.retrieval;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves natural language queries into CanonicalMetric objects using
 * boundary-aware span matching and phrase length precedence.
 */
@Component
public class MetricResolver {

    private record PhraseTarget(String phrase, CanonicalMetric metric) {}

    private final List<PhraseTarget> orderedTargets;

    public MetricResolver() {
        List<PhraseTarget> targets = new ArrayList<>();
        for (CanonicalMetric cm : CanonicalMetric.values()) {
            for (String phrase : cm.getTriggerPhrases()) {
                targets.add(new PhraseTarget(phrase.toLowerCase(), cm));
            }
        }
        // Sort by length descending, so longest phrases are matched first
        targets.sort((a, b) -> Integer.compare(b.phrase().length(), a.phrase().length()));
        this.orderedTargets = Collections.unmodifiableList(targets);
    }

    /**
     * Resolves all non-overlapping canonical metrics from the given query.
     * Metrics are returned in precedence order (longest match first, then earliest appearance).
     */
    public List<MetricResolution> resolve(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();
        List<MetricResolution> resolutions = new ArrayList<>();
        List<int[]> matchedSpans = new ArrayList<>();

        for (PhraseTarget target : orderedTargets) {
            String phrase = target.phrase();
            int startIndex = 0;

            while ((startIndex = lowerQuery.indexOf(phrase, startIndex)) != -1) {
                int endIndex = startIndex + phrase.length();
                
                // 1. Boundary check: ensure the phrase is not part of a larger word
                if (isIsolatedMatch(lowerQuery, startIndex, endIndex)) {
                    
                    // 2. Subsumption check: ensure this span isn't already covered by a longer phrase
                    if (!isSubsumed(startIndex, endIndex, matchedSpans)) {
                        
                        MetricResolution.MatchType type = phrase.equals(lowerQuery.trim()) 
                                ? MetricResolution.MatchType.EXACT 
                                : MetricResolution.MatchType.PHRASE;
                                
                        double score = type == MetricResolution.MatchType.EXACT ? 1.0 : 0.8;
                        
                        resolutions.add(new MetricResolution(
                                target.metric(), 
                                phrase, 
                                type, 
                                score, 
                                startIndex, 
                                endIndex
                        ));
                        
                        matchedSpans.add(new int[]{startIndex, endIndex});
                    }
                }
                
                startIndex += phrase.length();
            }
        }

        // Sort resolutions by length descending, then by startIndex ascending
        resolutions.sort((a, b) -> {
            int lenCompare = Integer.compare(b.matchedPhrase().length(), a.matchedPhrase().length());
            if (lenCompare != 0) return lenCompare;
            return Integer.compare(a.startIndex(), b.startIndex());
        });

        // Deduplicate: If multiple trigger phrases map to the same CanonicalMetric (and aren't subsumed), 
        // we only need to return the metric once. We keep the highest precedence one.
        List<MetricResolution> deduplicated = new ArrayList<>();
        Set<CanonicalMetric> seenMetrics = new HashSet<>();
        
        for (MetricResolution res : resolutions) {
            if (seenMetrics.add(res.canonicalMetric())) {
                deduplicated.add(res);
            }
        }

        return deduplicated;
    }

    /**
     * Checks if the matched span is surrounded by non-alphanumeric characters.
     * This acts as a safe word-boundary check that doesn't fail on special chars like '/' or '-'.
     */
    private boolean isIsolatedMatch(String text, int start, int end) {
        if (start > 0) {
            char before = text.charAt(start - 1);
            if (Character.isLetterOrDigit(before)) {
                return false;
            }
        }
        if (end < text.length()) {
            char after = text.charAt(end);
            if (Character.isLetterOrDigit(after)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if the given span is entirely contained within any of the already matched spans.
     */
    private boolean isSubsumed(int start, int end, List<int[]> matchedSpans) {
        for (int[] span : matchedSpans) {
            if (span[0] <= start && span[1] >= end) {
                return true;
            }
        }
        return false;
    }
}
