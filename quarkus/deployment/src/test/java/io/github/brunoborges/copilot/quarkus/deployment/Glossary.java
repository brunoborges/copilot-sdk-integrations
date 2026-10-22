package io.github.brunoborges.copilot.quarkus.deployment;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

@Singleton
@Named("glossary")
public class Glossary {

    @CopilotTool(value = "Define a term", name = "define")
    public String define(@CopilotToolParam("The term") String term) {
        return term + " means something";
    }
}
