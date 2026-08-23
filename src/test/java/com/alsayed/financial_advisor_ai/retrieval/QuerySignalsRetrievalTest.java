package com.alsayed.financial_advisor_ai.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySignalsRetrievalTest {

    private QueryAnalyzer queryAnalyzer;
    private EntityRegistry entityRegistry;

    @BeforeEach
    void setUp() {
        entityRegistry = Mockito.mock(EntityRegistry.class);
        MetricResolver metricResolver = new MetricResolver();
        queryAnalyzer = new QueryAnalyzer(entityRegistry, metricResolver);
    }

    @Test
    void testHighest1YearTotalReturn() {
        QuerySignals signals = queryAnalyzer.analyze("Which sector had the highest 1-year total return?");
        assertThat(signals.getCanonicalMetric()).isEqualTo(CanonicalMetric.TOTAL_RETURN);
        assertThat(signals.getPeriod()).isEqualTo("1-year");
        assertThat(signals.getOperation()).isEqualTo(QuerySignals.ComparisonOperation.MAXIMUM);
        assertThat(signals.getTableTypes()).contains("sector");
    }

    @Test
    void testDividendYield() {
        QuerySignals signals = queryAnalyzer.analyze("What is the dividend yield?");
        assertThat(signals.getCanonicalMetric()).isEqualTo(CanonicalMetric.DIVIDEND_YIELD);
    }

    @Test
    void testYieldToWorst() {
        QuerySignals signals = queryAnalyzer.analyze("What is the yield to worst?");
        assertThat(signals.getCanonicalMetric()).isEqualTo(CanonicalMetric.YIELD);
    }

    @Test
    void testYieldAndSpread() {
        QuerySignals signals = queryAnalyzer.analyze("What were the yield and spread for 10-year bonds?");
        assertThat(signals.getCanonicalMetrics()).containsExactlyInAnyOrder(CanonicalMetric.YIELD, CanonicalMetric.SPREAD);
    }

    @Test
    void testPolicyRate() {
        QuerySignals signals = queryAnalyzer.analyze("What is the policy rate?");
        assertThat(signals.getCanonicalMetric()).isEqualTo(CanonicalMetric.POLICY_RATE);
    }

    @Test
    void testImpliedPolicyRate() {
        QuerySignals signals = queryAnalyzer.analyze("What is the implied policy rate?");
        assertThat(signals.getCanonicalMetric()).isEqualTo(CanonicalMetric.IMPLIED_POLICY_RATE);
    }
}
