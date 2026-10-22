package io.github.brunoborges.copilot.agent.tools;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;

public class EchoTools implements EchoOperations {
    @Override
    @CopilotTool(value = "Echo a value", name = "echo")
    public String echo(@CopilotToolParam("Value to echo") String value) {
        return "echo:" + value;
    }
}
