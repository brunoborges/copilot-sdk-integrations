package com.microsoft.copilot.integrations.springboot.example;

import java.util.Map;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.SectionOverride;
import com.github.copilot.rpc.SectionOverrideAction;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.SystemMessageSections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    @Bean
    SystemMessageConfig advisorInstructions() {
        return new SystemMessageConfig().setMode(SystemMessageMode.CUSTOMIZE)
                .setSections(Map.of(SystemMessageSections.TONE, new SectionOverride()
                        .setAction(SectionOverrideAction.REPLACE)
                        .setContent("Be concise and explain concepts with Java examples.")))
                .setContent("Focus on Spring Boot applications. Answer without using tools.");
    }
}
