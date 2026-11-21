package com.microsoft.copilot.integrations.micronaut.test;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.microsoft.copilot.tool.CopilotTool;
import com.microsoft.copilot.tool.CopilotToolParam;

@Singleton
@Named("glossary")
public class Glossary {

    @CopilotTool(value = "Define a term", name = "define")
    public String define(@CopilotToolParam("The term") String term) {
        return term + " means something";
    }
}
