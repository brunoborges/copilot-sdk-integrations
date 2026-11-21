package com.microsoft.copilot.integrations.springboot.agent.tools;

import com.microsoft.copilot.tool.CopilotTool;
import com.microsoft.copilot.tool.CopilotToolParam;

public interface EchoOperations {
    @CopilotTool(value = "Echo a value", name = "echo")
    String echo(@CopilotToolParam("Value to echo") String value);
}
