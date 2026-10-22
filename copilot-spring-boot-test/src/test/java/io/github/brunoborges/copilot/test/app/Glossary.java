package io.github.brunoborges.copilot.test.app;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

@Component("glossary")
public class Glossary {

    private final List<String> lookups = new CopyOnWriteArrayList<>();

    @CopilotTool(value = "Describe a Spring term", name = "describe_term")
    public String describe(@CopilotToolParam("The term to describe") String term) {
        lookups.add(term);
        if (term.isBlank()) {
            throw new IllegalArgumentException("term must not be blank");
        }
        return term + ": a Spring concept";
    }

    public List<String> lookups() {
        return lookups;
    }
}
