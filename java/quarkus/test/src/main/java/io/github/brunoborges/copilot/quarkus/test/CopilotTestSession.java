package io.github.brunoborges.copilot.quarkus.test;

import java.util.List;
import java.util.Map;

import com.github.copilot.rpc.McpServerConfig;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;

/**
 * A snapshot of a Copilot session persisted by the {@link CopilotTestRuntime}.
 *
 * <p>The convenience accessors ({@link #model()}, {@link #tools()}, ...) report the configuration of the most recent
 * open of the session, i.e. the last resume configuration when the session was resumed, otherwise the creation
 * configuration.
 *
 * @param sessionId the session ID
 * @param agent the agent that created the session, or {@code null} when not created through a Copilot agent
 * @param config the configuration used to create the session
 * @param resumes the configurations used to resume the session, in order
 * @param prompts the prompts received by the session, in order
 */
public record CopilotTestSession(String sessionId, String agent, SessionConfig config,
        List<ResumeSessionConfig> resumes, List<String> prompts) {

    /** Whether the session has been resumed at least once. */
    public boolean resumed() {
        return !resumes.isEmpty();
    }

    /** The requested model, or {@code null} when the runtime default applies. */
    public String model() {
        return resumed() ? lastResume().getModel() : config.getModel();
    }

    /** The tools registered with the session. */
    public List<ToolDefinition> tools() {
        var tools = resumed() ? lastResume().getTools() : config.getTools();
        return tools == null ? List.of() : List.copyOf(tools);
    }

    /** The names of the tools registered with the session. */
    public List<String> toolNames() {
        return tools().stream().map(ToolDefinition::name).toList();
    }

    /** The system message configuration, or {@code null}. */
    public SystemMessageConfig systemMessage() {
        return resumed() ? lastResume().getSystemMessage() : config.getSystemMessage();
    }

    /** The MCP servers registered with the session. */
    public Map<String, McpServerConfig> mcpServers() {
        var servers = resumed() ? lastResume().getMcpServers() : config.getMcpServers();
        return servers == null ? Map.of() : Map.copyOf(servers);
    }

    private ResumeSessionConfig lastResume() {
        return resumes.get(resumes.size() - 1);
    }
}
