package com.alsayed.financial_advisor_ai;

import com.alsayed.financial_advisor_ai.chunking.figure.FigureChunkPolicy;
import com.alsayed.financial_advisor_ai.chunking.table.TableChunkPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

@SpringBootApplication
@EnableConfigurationProperties({FigureChunkPolicy.class, TableChunkPolicy.class})
public class Application {

    static {
        Locale.setDefault(Locale.US);
        loadDotenv();
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    private static void loadDotenv() {
        File envFile = new File(".env");
        if (envFile.exists()) {
            try {
                for (String line : Files.readAllLines(envFile.toPath())) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        String[] parts = trimmed.split("=", 2);
                        String key = parts[0].trim();
                        String val = parts[1].trim();
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, val);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Notice: Could not read .env file: " + e.getMessage());
            }
        }
    }
}