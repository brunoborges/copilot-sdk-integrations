package com.microsoft.copilot.integrations.micronaut.example;

import java.util.Map;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.microsoft.copilot.SystemMessageMode;
import com.microsoft.copilot.rpc.SectionOverride;
import com.microsoft.copilot.rpc.SectionOverrideAction;
import com.microsoft.copilot.rpc.SystemMessageConfig;
import com.microsoft.copilot.rpc.SystemMessageSections;

import io.micronaut.context.annotation.Factory;

@Factory
public class AgentConfiguration {

    @Singleton
    @Named("advisorInstructions")
    SystemMessageConfig advisorInstructions() {
        return new SystemMessageConfig().setMode(SystemMessageMode.CUSTOMIZE)
                .setSections(Map.of(SystemMessageSections.TONE, new SectionOverride()
                        .setAction(SectionOverrideAction.REPLACE)
                        .setContent("Be concise and explain concepts with Java examples.")))
                .setContent("Focus on Micronaut applications. Answer without using tools.");
    }
}
