package com.alsayed.financial_advisor_ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ExactDatapointCountReconciliationTest {

    @Test
    void reconcileDatapointsExactly() throws Exception {
        Path cacheDir = Path.of(".vision-cache");
        ObjectMapper mapper = new ObjectMapper();

        int totalCacheFiles = 0;
        int totalJsonDataPointObjects = 0;
        int validNumericValues = 0;
        int nonNumericOrEmptyValues = 0;
        int totalPanels = 0;

        Map<String, Integer> fileCounts = new LinkedHashMap<>();

        try (var stream = Files.list(cacheDir)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            for (Path f : files) {
                totalCacheFiles++;
                String raw = Files.readString(f).trim();
                if (raw.startsWith("```json")) raw = raw.substring(7);
                else if (raw.startsWith("```")) raw = raw.substring(3);
                if (raw.endsWith("```")) raw = raw.substring(0, raw.length() - 3);

                int fileDps = 0;
                try {
                    JsonNode root = mapper.readTree(raw);
                    if (root.has("panels") && root.get("panels").isArray()) {
                        for (JsonNode panel : root.get("panels")) {
                            totalPanels++;
                            if (panel.has("data_points") && panel.get("data_points").isArray()) {
                                for (JsonNode dp : panel.get("data_points")) {
                                    totalJsonDataPointObjects++;
                                    fileDps++;
                                    if (dp.has("value") && !dp.get("value").isNull()) {
                                        String val = dp.get("value").asText().trim();
                                        if (val.matches("^-?\\d+(\\.\\d+)?%?$") || val.matches("^-?\\$\\d+(\\.\\d+)?$") || val.matches("^-?\\d+(\\.\\d+)?x$")) {
                                            validNumericValues++;
                                        } else {
                                            nonNumericOrEmptyValues++;
                                        }
                                    } else {
                                        nonNumericOrEmptyValues++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
                fileCounts.put(f.getFileName().toString(), fileDps);
            }
        }

        System.out.println("==========================================================================================");
        System.out.println("EXACT MATHEMATICAL RECONCILIATION OF VISION CACHE DATAPOINTS");
        System.out.println("==========================================================================================");
        System.out.printf("Total .vision-cache JSON files:              %d\n", totalCacheFiles);
        System.out.printf("Total Panels across all files:               %d\n", totalPanels);
        System.out.printf("Total Json FigureDataPoint objects parsed:   %d\n", totalJsonDataPointObjects);
        System.out.printf("  - Valid numerical values (explicit):       %d\n", validNumericValues);
        System.out.printf("  - Non-numeric / Empty / Null values:       %d\n", nonNumericOrEmptyValues);
        System.out.println("==========================================================================================");
    }
}
