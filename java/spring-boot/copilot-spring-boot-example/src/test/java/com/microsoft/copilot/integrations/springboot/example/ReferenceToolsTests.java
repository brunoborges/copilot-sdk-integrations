package com.microsoft.copilot.integrations.springboot.example;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolInvocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceToolsTests {
    @Test
    void sdkProcessorGeneratesExecutableToolMetadataForTheSpringService() {
        var tools = ToolDefinition.fromObject(new ReferenceTools());
        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("describe_term");
        var invocation = new ToolInvocation().setArguments(
                new ObjectMapper().valueToTree(Map.of("term", "dependency injection")));
        assertThat(tools.getFirst().handler().invoke(invocation).join()).asString().contains("supplied");
        assertThat(tools.getFirst().skipPermission()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void unknownTermsAreErrorsRatherThanInventedReferenceData() {
        assertThatThrownBy(() -> new ReferenceTools().describeTerm("unknown"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown reference term");
    }
}
