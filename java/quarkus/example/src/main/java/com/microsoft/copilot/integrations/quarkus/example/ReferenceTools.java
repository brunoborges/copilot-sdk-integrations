package com.microsoft.copilot.integrations.quarkus.example;

import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

@ApplicationScoped
@Named("referenceTools")
public class ReferenceTools {

    @CopilotTool(value = "Look up a short definition from local Quarkus/Java reference data", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Quarkus/Java term to describe") String term) {
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("Reference term must not be blank");
        }
        return switch (term.toLowerCase(Locale.ROOT)) {
            case "dependency injection" -> "Dependencies are supplied to an object rather than constructed inside it.";
            case "virtual threads" -> "Lightweight JVM threads let blocking tasks scale without dedicating a platform thread to each task.";
            case "quarkus" -> "Quarkus moves framework work to build time so Java applications start fast and use little memory.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
