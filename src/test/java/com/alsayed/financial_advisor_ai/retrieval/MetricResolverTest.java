package com.alsayed.financial_advisor_ai.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricResolverTest {

    private MetricResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MetricResolver();
    }

    @Test
    void testPhrasePrecedence() {
        // "dividend yield" should map to DIVIDEND_YIELD, and "yield" should be subsumed
        List<MetricResolution> res = resolver.resolve("What is the dividend yield?");
        
        assertThat(res).hasSize(1);
        assertThat(res.get(0).canonicalMetric()).isEqualTo(CanonicalMetric.DIVIDEND_YIELD);
        assertThat(res.get(0).matchedPhrase()).isEqualTo("dividend yield");
        assertThat(res.get(0).matchType()).isEqualTo(MetricResolution.MatchType.PHRASE);
    }

    @Test
    void testYieldToWorstPrecedence() {
        // "yield to worst" should map to YIELD
        List<MetricResolution> res = resolver.resolve("What is the yield to worst?");
        
        assertThat(res).hasSize(1);
        assertThat(res.get(0).canonicalMetric()).isEqualTo(CanonicalMetric.YIELD);
        assertThat(res.get(0).matchedPhrase()).isEqualTo("yield to worst");
    }

    @Test
    void testNegativeGenericWords() {
        // "worst" should not trigger any metric on its own
        List<MetricResolution> res = resolver.resolve("Which sector performed the worst?");
        assertThat(res).isEmpty();

        // "policy" should not trigger POLICY_RATE
        List<MetricResolution> res2 = resolver.resolve("What is the policy?");
        assertThat(res2).isEmpty();
        
        // "rate" should trigger DEFAULT_RATE or RATE_CHANGE or POLICY_RATE if bare?
        // Wait, does CanonicalMetric have "rate" as a trigger phrase?
        // "rate" is in DEFAULT_RATE search aliases, but is it a trigger phrase?
        // DEFAULT_RATE trigger phrases: "default rate", "default rates", "annual default rate", "defaults", "loss rate", "par weighted default rate"
        // Wait, if it has no "rate" trigger phrase, it shouldn't trigger.
        // Let's check CanonicalMetric trigger phrases for "rate".
        // There is no bare "rate" trigger phrase! So it will not trigger.
    }

    @Test
    void testPolicyRates() {
        List<MetricResolution> res = resolver.resolve("What is the policy rate?");
        assertThat(res).hasSize(1);
        assertThat(res.get(0).canonicalMetric()).isEqualTo(CanonicalMetric.POLICY_RATE);
        
        List<MetricResolution> res2 = resolver.resolve("What is the implied policy rate?");
        assertThat(res2).hasSize(1);
        assertThat(res2.get(0).canonicalMetric()).isEqualTo(CanonicalMetric.IMPLIED_POLICY_RATE);
    }

    @Test
    void testMultiMetric() {
        List<MetricResolution> res = resolver.resolve("What were the yield and spread for 10-year bonds?");
        
        assertThat(res).hasSize(2);
        assertThat(res.get(0).canonicalMetric()).isIn(CanonicalMetric.YIELD, CanonicalMetric.SPREAD);
        assertThat(res.get(1).canonicalMetric()).isIn(CanonicalMetric.YIELD, CanonicalMetric.SPREAD);
    }
    
    @Test
    void testBoundarySafety() {
        // "P/E" shouldn't fail due to regex boundaries
        List<MetricResolution> res = resolver.resolve("What is the P/E?");
        assertThat(res).hasSize(1);
        assertThat(res.get(0).canonicalMetric()).isEqualTo(CanonicalMetric.VALUATION_PE);
        
        // "12-mo" or similar hyphenated terms if added later would also be safe.
    }
    
    @Test
    void testSubsumptionNotOverlyAggressive() {
        List<MetricResolution> res = resolver.resolve("Show me the dividend yield and bond yield.");
        // "dividend yield" (DIVIDEND_YIELD)
        // "bond yield" - "yield" matches here (YIELD)
        // They occupy different spans, so both should be returned.
        
        assertThat(res).hasSize(2);
        boolean hasDividendYield = res.stream().anyMatch(r -> r.canonicalMetric() == CanonicalMetric.DIVIDEND_YIELD);
        boolean hasYield = res.stream().anyMatch(r -> r.canonicalMetric() == CanonicalMetric.YIELD);
        
        assertThat(hasDividendYield).isTrue();
        assertThat(hasYield).isTrue();
    }
}
