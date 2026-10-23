package workshop.common;

import com.github.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.integrations.core.CopilotPermissionPolicies;

public final class ToolPermissions {
    private ToolPermissions() {}
    public static PermissionHandler museum(MuseumTools tools) {
        return CopilotPermissionPolicies.allowCustomTools(
                new MuseumTools$$CopilotToolMeta().definitions(tools, Json.MAPPER));
    }
    public static PermissionHandler accessibility(AccessibilityTools tools) {
        return CopilotPermissionPolicies.allowCustomTools(
                new AccessibilityTools$$CopilotToolMeta().definitions(tools, Json.MAPPER));
    }
}
