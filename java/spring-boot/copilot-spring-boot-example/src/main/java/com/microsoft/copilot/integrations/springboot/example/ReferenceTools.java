package com.microsoft.copilot.integrations.springboot.example;

import java.util.Locale;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class ReferenceTools {

    @CopilotTool(value = "Look up a short definition from local Spring/Java reference data", name = "describe_term")
    public String describeTerm(@CopilotToolParam("Spring/Java term to describe") String term) {
        Assert.hasText(term, "Reference term must not be blank");
        return switch (term.toLowerCase(Locale.ROOT)) {
            case "dependency injection" -> "Dependencies are supplied to an object rather than constructed inside it.";
            case "virtual threads" -> "Lightweight JVM threads let blocking tasks scale without dedicating a platform thread to each task.";
            case "spring boot" -> "Spring Boot configures Spring applications using conventions and conditional auto-configuration.";
            default -> throw new IllegalArgumentException("Unknown reference term");
        };
    }
}
