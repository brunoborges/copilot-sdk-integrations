package com.microsoft.copilot.integrations.springboot.agent.tools;

import com.github.copilot.tool.CopilotTool;
import com.github.copilot.tool.CopilotToolParam;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityAwareTools {
    @CopilotTool(value = "Describe the calling user", name = "whoami")
    public String whoami(@CopilotToolParam("Prefix") String prefix) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("Access denied: no authenticated user");
        }
        return prefix + authentication.getName() + "/" + MDC.get("requestId");
    }
}
