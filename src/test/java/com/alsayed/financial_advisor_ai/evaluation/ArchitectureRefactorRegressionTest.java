package com.alsayed.financial_advisor_ai.evaluation;

import com.alsayed.financial_advisor_ai.retrieval.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("live-api")
@SpringBootTest(properties = {
        "spring.config.name=application-test",
        "app.vision.enabled=true",
        "app.rag.search.hybrid.enabled=true",
        "app.ingestion.force=false"
})
public class ArchitectureRefactorRegressionTest {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryAnalyzer queryAnalyzer;

    @Test
    void testUnitAndMetricSeparation() {
        String query = "How many basis points of rate cuts are priced in over the next two years for three central banks?";
        QuerySignals signals = queryAnalyzer.analyze(query);
        
        assertEquals(CanonicalMetric.RATE_CHANGE, signals.getCanonicalMetric(), "Metric should be RATE_CHANGE, not SPREAD");
        assertEquals("bps", signals.getCanonicalUnit(), "Unit should be explicitly detected as bps");
        assertTrue(signals.getDerivedEvidenceMetrics().contains("POLICY_RATE"), "Should expand to POLICY_RATE");
        assertTrue(signals.getDerivedEvidenceMetrics().contains("IMPLIED_POLICY_RATE"), "Should expand to IMPLIED_POLICY_RATE");
    }

    @Test
    void testDirectPolicyRateQuery() {
        String query = "What is the current policy rate for the Fed?";
        QuerySignals signals = queryAnalyzer.analyze(query);
        assertEquals(CanonicalMetric.POLICY_RATE, signals.getCanonicalMetric());
        assertTrue(signals.getDerivedEvidenceMetrics() == null || signals.getDerivedEvidenceMetrics().isEmpty(), "No derived metrics needed for direct query");
    }

    @Test
    void testImpliedPolicyRateQuery() {
        String query = "What is the implied policy rate in two years?";
        QuerySignals signals = queryAnalyzer.analyze(query);
        assertEquals(CanonicalMetric.IMPLIED_POLICY_RATE, signals.getCanonicalMetric());
    }

    @Test
    void testGenericBasisPointSpread() {
        String query = "What is the sovereign spread in basis points?";
        QuerySignals signals = queryAnalyzer.analyze(query);
        assertEquals(CanonicalMetric.SPREAD, signals.getCanonicalMetric());
        assertEquals("bps", signals.getCanonicalUnit());
    }
}
