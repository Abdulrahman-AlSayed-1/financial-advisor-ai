package com.alsayed.financial_advisor_ai.retrieval;

import lombok.Getter;

import java.util.*;

/**
 * Canonical semantic financial metrics.
 * Decouples user question vocabulary from document-level metadata and provides
 * bidirectional aliases for lexical, dense, and structured SQL retrieval.
 */
@Getter
public enum CanonicalMetric {
    POLICY_RATE(
        "POLICY_RATE",
        Set.of("policy rate", "policy rates", "central bank rate", "central bank rates", 
               "central bank policy rate", "central bank policy rates", "interest rate", 
               "interest rates", "target rate", "target range", "cash rate", "repo rate", 
               "fed funds", "federal funds", "monetary policy rate", "bank rate", "current rate"),
        List.of("central bank rates", "central bank", "policy rate", "central bank policy rates", "monetary policy", "target rate", "fed funds"),
        List.of()
    ),
    RATE_CHANGE(
        "RATE_CHANGE",
        Set.of("rate cut", "rate cuts", "rate hike", "rate hikes", "cut", "hike", "change in rate", "rate change"),
        List.of("rate cuts", "rate hikes", "cut", "hike", "change in rate"),
        List.of("POLICY_RATE", "IMPLIED_POLICY_RATE")
    ),
    IMPLIED_POLICY_RATE(
        "IMPLIED_POLICY_RATE",
        Set.of("implied policy rate", "implied policy rates", "implied rate", "implied rates", "priced in", "market expectation", "forward rate", "market expectations"),
        List.of("implied policy rates", "implied rate", "priced in", "market expectation", "forward rate"),
        List.of()
    ),
    INFLOW(
        "INFLOW",
        Set.of("inflows", "inflow", "fund inflows", "monthly inflows", "net inflows", "largest inflows", "most inflows", "highest inflows"),
        List.of("inflows", "inflow", "flows", "flow", "monthly flows", "net flows", "fund flows", "exchange-traded fund"),
        List.of()
    ),
    OUTFLOW(
        "OUTFLOW",
        Set.of("outflows", "outflow", "fund outflows", "monthly outflows", "net outflows", "largest outflows", "most outflows"),
        List.of("outflows", "outflow", "flows", "flow", "monthly flows", "net flows"),
        List.of()
    ),
    FLOW(
        "FLOW",
        Set.of("flows", "flow", "monthly flows", "net flows", "etf flows", "fund flows"),
        List.of("monthly flows", "flows", "flow", "inflows", "inflow", "net flows", "fund flows", "exchange-traded fund"),
        List.of()
    ),
    DEFAULT_RATE(
        "DEFAULT_RATE",
        Set.of("default rate", "default rates", "annual default rate", "defaults", "loss rate", "par weighted default rate"),
        List.of("default rate", "annual default rate", "default", "rate"),
        List.of()
    ),
    VALUATION_PE(
        "VALUATION_PE",
        Set.of("ntm p/e ratio", "ntm pe ratio", "ntm p/e", "ntm pe", "p/e ratio", "pe ratio", 
               "price/earnings", "valuation multiple", "forward p/e", "forward pe", "p/e", "pe", 
               "price to earnings", "valuation", "multiple"),
        List.of("ntm p/e", "p/e", "pe", "valuation", "multiple", "price/earnings"),
        List.of()
    ),
    TOTAL_RETURN(
        "TOTAL_RETURN",
        Set.of("total return", "total returns", "spot return", "spot returns", "ytd return", 
               "monthly return", "3-month return", "1-year return", "return", "returns", "performance"),
        List.of("total return", "return", "spot returns", "returns", "performance"),
        List.of()
    ),
    DIVIDEND_YIELD(
        "DIVIDEND_YIELD",
        Set.of("dividend yield", "dividend yields", "div yield", "div yields", "dividend payout yield", "dividend payout", "dividend"),
        List.of("dividend yield", "dividend", "yield", "div yield", "characteristics", "fundamentals"),
        List.of()
    ),
    YIELD(
        "YIELD",
        Set.of("yield to worst", "yield-to-worst", "ytw", "treasury yield", "sovereign yield", 
               "benchmark yield", "yield", "yields"),
        List.of("yield to worst", "yield", "ytw", "spread"),
        List.of()
    ),
    SPREAD(
        "SPREAD",
        Set.of("option-adjusted spread", "oas", "credit spread", "bond spread", "average spread", 
               "spread", "spreads"),
        List.of("spread", "avg. spread"),
        List.of()
    ),
    CORRELATION(
        "CORRELATION",
        Set.of("correlation", "correlations", "cross-asset correlation"),
        List.of("correlation", "correlations"),
        List.of()
    ),
    SECTOR_WEIGHT(
        "SECTOR_WEIGHT",
        Set.of("sector weight", "sector weights", "index weight", "index weights", 
               "sector weighting", "sector weightings", "sector allocation", "sector allocations", 
               "sector exposure", "weight", "weights", "weighting", "allocation"),
        List.of("sector weight", "sector weights", "weights", "weight", "characteristics and sector weights", "sector"),
        List.of()
    ),
    COUPON(
        "COUPON",
        Set.of("coupon", "coupons", "coupon rate"),
        List.of("coupon", "coupon rate"),
        List.of()
    ),
    DURATION(
        "DURATION",
        Set.of("duration", "effective duration", "modified duration", "macaulay duration"),
        List.of("duration", "effective duration", "interest rate risk"),
        List.of()
    ),
    CONVEXITY(
        "CONVEXITY",
        Set.of("convexity", "effective convexity"),
        List.of("convexity"),
        List.of()
    ),
    PRICE(
        "PRICE",
        Set.of("price", "prices", "level", "levels"),
        List.of("price", "level"),
        List.of()
    );

    private final String identifier;
    private final Set<String> triggerPhrases;
    private final List<String> searchAliases;
    private final List<String> underlyingEvidenceMetrics;

    CanonicalMetric(String identifier, Set<String> triggerPhrases, List<String> searchAliases, List<String> underlyingEvidenceMetrics) {
        this.identifier = identifier;
        this.triggerPhrases = triggerPhrases;
        this.searchAliases = searchAliases;
        this.underlyingEvidenceMetrics = underlyingEvidenceMetrics;
    }

}
