package com.microsoft.copilot.integrations.quarkus.example;

import java.util.Map;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.microsoft.copilot.SystemMessageMode;
import com.microsoft.copilot.rpc.SectionOverride;
import com.microsoft.copilot.rpc.SectionOverrideAction;
import com.microsoft.copilot.rpc.SystemMessageConfig;
import com.microsoft.copilot.rpc.SystemMessageSections;

@Singleton
public class AgentConfiguration {

    @Produces
    @Singleton
    @Named("advisorInstructions")
    SystemMessageConfig advisorInstructions() {
        return new SystemMessageConfig().setMode(SystemMessageMode.CUSTOMIZE)
                .setSections(Map.of(SystemMessageSections.TONE, new SectionOverride()
                        .setAction(SectionOverrideAction.REPLACE)
                        .setContent("Be concise and explain concepts with Java examples.")))
                .setContent("Focus on Quarkus applications. Answer without using tools.");
    }
}
