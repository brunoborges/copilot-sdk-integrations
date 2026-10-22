package io.github.brunoborges.copilot.quarkus.example;

import java.util.Map;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.SectionOverride;
import com.github.copilot.rpc.SectionOverrideAction;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.SystemMessageSections;

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
