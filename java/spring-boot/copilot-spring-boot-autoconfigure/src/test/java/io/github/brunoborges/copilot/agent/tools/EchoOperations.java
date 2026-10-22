package io.github.brunoborges.copilot.agent.tools;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

public interface EchoOperations {
    @CopilotTool(value = "Echo a value", name = "echo")
    String echo(@CopilotToolParam("Value to echo") String value);
}
