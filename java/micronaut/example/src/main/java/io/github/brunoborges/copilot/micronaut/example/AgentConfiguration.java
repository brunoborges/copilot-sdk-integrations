package io.github.brunoborges.copilot.micronaut.example;

import java.util.Map;

import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.SectionOverride;
import com.github.copilot.rpc.SectionOverrideAction;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.SystemMessageSections;

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
