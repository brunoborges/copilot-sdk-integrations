package io.github.brunoborges.copilot.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.ToolDefinition;
import org.springframework.util.Assert;

/**
 * Ready-made SDK {@link PermissionHandler} policies for {@link CopilotAgent#permissionHandler()} beans.
 *
 * <p>Policies built here approve a request once when one of their rules matches and otherwise reject it,
 * logging the denied request kind. Requests the runtime marks as requiring managed (human) approval are never
 * approved automatically, except by {@link #allowAll()}, which follows the SDK's own approve-all semantics.
 *
 * <p>Request kinds sent by the Copilot runtime include {@value #SHELL}, {@value #WRITE}, {@value #READ},
 * {@value #MCP}, {@value #CUSTOM_TOOL}, {@value #URL}, {@value #MEMORY}, and {@value #HOOK}; new kinds may be added,
 * so policies deny anything they do not recognize.
 */
public final class CopilotPermissionPolicies {

    /** Executing a shell command. */
    public static final String SHELL = "shell";
    /** Writing or editing a file. */
    public static final String WRITE = "write";
    /** Reading a file. */
    public static final String READ = "read";
    /** Calling an MCP server tool. */
    public static final String MCP = "mcp";
    /** Calling a tool registered by the application, such as an agent's {@link CopilotAgent#tools()}. */
    public static final String CUSTOM_TOOL = "custom-tool";
    /** Fetching a URL. */
    public static final String URL = "url";
    /** Storing or retrieving persistent session memory. */
    public static final String MEMORY = "memory";
    /** Confirming a runtime hook. */
    public static final String HOOK = "hook";

    private static final Logger log = Logger.getLogger(CopilotPermissionPolicies.class.getName());

    private CopilotPermissionPolicies() {
    }

    /** @return a policy that rejects every request */
    public static PermissionHandler denyAll() {
        return new Policy("deny all", request -> false);
    }

    /**
     * Approves every request, exactly like the SDK's {@link PermissionHandler#APPROVE_ALL}.
     *
     * <p><strong>Dangerous:</strong> the model can then run shell commands, write files, and fetch URLs without
     * any check. Use only in isolated sandboxes, ideally together with a narrow
     * {@link CopilotAgent#availableTools()} allow-list.
     *
     * @return the SDK approve-all handler
     */
    public static PermissionHandler allowAll() {
        return PermissionHandler.APPROVE_ALL;
    }

    /**
     * @param kinds permission request kinds approved unconditionally, such as {@link #READ} or {@link #URL}
     * @return a policy approving only the given kinds
     */
    public static PermissionHandler allowKinds(String... kinds) {
        return allowKinds(Arrays.asList(kinds));
    }

    /**
     * @param kinds permission request kinds approved unconditionally
     * @return a policy approving only the given kinds
     */
    public static PermissionHandler allowKinds(Collection<String> kinds) {
        var allowed = names(kinds, "kinds");
        return new Policy("allow kinds " + allowed, request -> contains(allowed, request.getKind()));
    }

    /**
     * Approves {@value #CUSTOM_TOOL} and {@value #MCP} requests for the named tools. MCP tool names are the server's
     * own tool names, as reported in the request's {@code toolName}.
     *
     * @param toolNames tool names to approve
     * @return a policy approving calls to the given tools
     */
    public static PermissionHandler allowTools(String... toolNames) {
        return allowTools(Arrays.asList(toolNames));
    }

    /**
     * @param toolNames tool names to approve for {@value #CUSTOM_TOOL} and {@value #MCP} requests
     * @return a policy approving calls to the given tools
     */
    public static PermissionHandler allowTools(Collection<String> toolNames) {
        var allowed = names(toolNames, "toolNames");
        return new Policy("allow tools " + allowed, request -> (CUSTOM_TOOL.equals(request.getKind())
                || MCP.equals(request.getKind())) && contains(allowed, text(request, "toolName")));
    }

    /**
     * Approves {@value #CUSTOM_TOOL} requests for the given SDK tool definitions, typically an agent's own
     * Spring {@code @CopilotTool} tools. Namespaced tools match their plain or {@code namespace.name} form.
     *
     * @param tools tool definitions to approve
     * @return a policy approving calls to the given application tools
     */
    public static PermissionHandler allowCustomTools(Collection<ToolDefinition> tools) {
        Assert.notNull(tools, "tools must not be null");
        var names = tools.stream().<String>mapMulti((tool, sink) -> {
            sink.accept(tool.name());
            if (tool.namespace() != null && tool.namespace().name() != null) {
                sink.accept(tool.namespace().name() + "." + tool.name());
            }
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Policy("allow custom tools " + names,
                request -> CUSTOM_TOOL.equals(request.getKind()) && contains(names, text(request, "toolName")));
    }

    /**
     * @param serverNames MCP server names, as configured for the agent
     * @return a policy approving every tool call to the given MCP servers
     */
    public static PermissionHandler allowMcpServers(String... serverNames) {
        return allowMcpServers(Arrays.asList(serverNames));
    }

    /**
     * @param serverNames MCP server names, as configured for the agent
     * @return a policy approving every tool call to the given MCP servers
     */
    public static PermissionHandler allowMcpServers(Collection<String> serverNames) {
        var allowed = names(serverNames, "serverNames");
        return new Policy("allow MCP servers " + allowed,
                request -> MCP.equals(request.getKind()) && contains(allowed, text(request, "serverName")));
    }

    /**
     * Approves {@value #READ} requests and {@value #MCP} tool calls the server declares read-only.
     *
     * @return a read-only policy
     */
    public static PermissionHandler allowReadOnly() {
        return new Policy("allow read-only", request -> READ.equals(request.getKind())
                || (MCP.equals(request.getKind()) && Boolean.TRUE.equals(value(request, "readOnly"))));
    }

    /**
     * Combines handlers so a request is approved when any of them approves it, asking them in order and
     * returning the first approval. A handler failure is logged and treated as a denial. When no handler approves,
     * the request is rejected and logged once.
     *
     * @param handlers handlers to consult in order
     * @return the composed handler
     */
    public static PermissionHandler anyOf(PermissionHandler... handlers) {
        return anyOf(Arrays.asList(handlers));
    }

    /**
     * @param handlers handlers to consult in order
     * @return the composed handler
     * @see #anyOf(PermissionHandler...)
     */
    public static PermissionHandler anyOf(List<PermissionHandler> handlers) {
        Assert.notNull(handlers, "handlers must not be null");
        Assert.noNullElements(handlers, "handlers must not contain null");
        var delegates = List.copyOf(handlers);
        if (delegates.stream().allMatch(Policy.class::isInstance)) {
            var policies = delegates.stream().map(Policy.class::cast).toList();
            return new Policy("any of " + policies,
                    request -> policies.stream().anyMatch(policy -> policy.matches(request)));
        }
        return (request, invocation) -> next(delegates, 0, request, invocation);
    }

    private static CompletableFuture<PermissionRequestResult> next(List<PermissionHandler> handlers, int index,
            PermissionRequest request, PermissionInvocation invocation) {
        if (index == handlers.size()) {
            return CompletableFuture.completedFuture(deny(request, "no composed policy approved it"));
        }
        CompletableFuture<PermissionRequestResult> result;
        try {
            result = Objects.requireNonNull(handlers.get(index).handle(request, invocation),
                    "Permission handler returned a null future");
        } catch (RuntimeException ex) {
            result = CompletableFuture.failedFuture(ex);
        }
        return result.handle((value, error) -> {
            if (error != null) {
                log.log(Level.WARNING, "Copilot permission handler failed; treating it as a denial", error);
                return null;
            }
            return isApproval(value) ? value : null;
        }).thenCompose(value -> value != null ? CompletableFuture.completedFuture(value)
                : next(handlers, index + 1, request, invocation));
    }

    /**
     * @param result an SDK permission result
     * @return whether the result grants the request
     */
    public static boolean isApproval(PermissionRequestResult result) {
        return result != null && result.getKind() != null && result.getKind().startsWith("approve");
    }

    private static PermissionRequestResult deny(PermissionRequest request, String reason) {
        log.warning("Copilot agent denied permission request of kind '" + request.getKind() + "' (" + reason + ").");
        return PermissionRequestResult.reject("Permission request of kind '" + request.getKind()
                + "' is not allowed by this agent's permission policy.");
    }

    private static Set<String> names(Collection<String> values, String description) {
        Assert.notNull(values, description + " must not be null");
        values.forEach(value -> Assert.hasText(value, description + " must not contain blank entries"));
        return Set.copyOf(values);
    }

    private static boolean contains(Set<String> values, String value) {
        return value != null && values.contains(value);
    }

    private static Object value(PermissionRequest request, String key) {
        Map<String, Object> data = request.getExtensionData();
        return data == null ? null : data.get(key);
    }

    private static String text(PermissionRequest request, String key) {
        return value(request, key) instanceof String text ? text : null;
    }

    private record Policy(String description, Predicate<PermissionRequest> rule) implements PermissionHandler {

        boolean matches(PermissionRequest request) {
            return !Boolean.TRUE.equals(request.getManagedApprovalRequired()) && rule.test(request);
        }

        @Override
        public CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
            boolean approved;
            try {
                approved = matches(request);
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Copilot permission policy failed; denying the request", ex);
                approved = false;
            }
            return CompletableFuture.completedFuture(approved ? PermissionRequestResult.approveOnce()
                    : deny(request, description));
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
