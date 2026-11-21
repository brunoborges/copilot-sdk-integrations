package com.microsoft.copilot.integrations.springboot.agent.tools;

import com.microsoft.copilot.tool.CopilotTool;
import com.microsoft.copilot.tool.CopilotToolParam;

public class EchoTools implements EchoOperations {
    @Override
    @CopilotTool(value = "Echo a value", name = "echo")
    public String echo(@CopilotToolParam("Value to echo") String value) {
        return "echo:" + value;
    }
}
