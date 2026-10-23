package com.microsoft.copilot.integrations.micronaut.example;

import java.util.Locale;

import jakarta.inject.Singleton;
import jakarta.inject.Named;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

@Singleton
@Named("referenceTools")
public class ReferenceTools {

    @CopilotTool(value = "Look up a short definition from local Micronaut/Java reference data", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Micronaut/Java term to describe") String term) {
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("Reference term must not be blank");
        }
        return switch (term.toLowerCase(Locale.ROOT)) {
            case "dependency injection" -> "Dependencies are supplied to an object rather than constructed inside it.";
            case "virtual threads" -> "Lightweight JVM threads let blocking tasks scale without dedicating a platform thread to each task.";
            case "micronaut" -> "Micronaut computes dependency injection at compile time, so applications start fast without reflection.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
