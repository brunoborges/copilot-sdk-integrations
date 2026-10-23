package io.github.brunoborges.copilot.camel;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.apache.camel.Consumer;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultEndpoint;

import com.github.copilot.SystemMessageMode;

import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentSettings;
import io.github.brunoborges.copilot.agent.internal.StructuredResponse;

/**
 * {@code copilot:agent:<name>} – sends the message to a Copilot agent and replaces the body with its reply.
 *
 * <p>The agent is either a {@code @CopilotAgent} interface registered with the component's {@code agentTypes}
 * option, or configured entirely by this endpoint's options. Endpoint-configured agents run without built-in CLI
 * tools unless {@code builtInTools=true}, and deny every permission request unless a permission option or
 * {@code permissionHandlerRef} allows it.
 */
public class CopilotAgentEndpoint extends DefaultEndpoint {

    private final String name;

    private String prompt;
    private String conversationId;
    private String outputClass;

    private String instructions;
    private String instructionsResource;
    private SystemMessageMode instructionsMode;
    private String systemMessageRef;
    private String model;
    private String reasoningEffort;
    private String permissionHandlerRef;
    private String toolRefs;
    private String mcpServers;
    private String provider;
    private String listenerRefs;
    private String customizerRefs;
    private String availableTools;
    private String excludedTools;
    private String excludedBuiltInAgents;
    private Boolean builtInTools;
    private Boolean toolSearch;
    private Integer toolSearchDeferThreshold;
    private String workingDirectory;
    private String additionalDirectories;
    private String hookRefs;
    private String tokenResolverRef;
    private String messageSource;
    private Duration responseTimeout;
    private String allow;
    private String allowTools;
    private String allowMcpServers;
    private Boolean allowAgentTools;
    private Boolean allowReadOnly;

    private volatile CopilotAgentDefinition definition;
    private volatile StructuredResponse structured;
    private Expression promptExpression;
    private Expression conversationIdExpression;

    CopilotAgentEndpoint(String uri, CopilotComponent component, String name) {
        super(uri, component);
        this.name = name;
    }

    @Override
    public CopilotComponent getComponent() {
        return (CopilotComponent) super.getComponent();
    }

    @Override
    public Producer createProducer() {
        return new CopilotAgentProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("copilot:agent is a producer; use copilot:tool, copilot:permission "
                + "or copilot:events to consume");
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();
        var simple = getCamelContext().resolveLanguage("simple");
        promptExpression = prompt == null ? null : simple.createExpression(prompt);
        conversationIdExpression = conversationId == null ? null : simple.createExpression(conversationId);
        if (outputClass != null) {
            structured = StructuredResponse.of(getCamelContext().getClassResolver().resolveMandatoryClass(outputClass));
        }
    }

    String agentName() {
        return name;
    }

    StructuredResponse structured() {
        return structured;
    }

    String prompt(Exchange exchange) {
        String text = promptExpression == null ? exchange.getMessage().getBody(String.class)
                : promptExpression.evaluate(exchange, String.class);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("copilot:agent:" + name + " requires a non-empty prompt");
        }
        return text;
    }

    String conversationId(Exchange exchange) {
        String id = conversationIdExpression != null ? conversationIdExpression.evaluate(exchange, String.class)
                : exchange.getMessage().getHeader(CopilotConstants.CONVERSATION_ID, String.class);
        return id == null || id.isBlank() ? null : id;
    }

    /** Resolves the agent on first use, after every tool, permission and event route has been registered. */
    CopilotAgentDefinition definition() {
        var current = definition;
        if (current == null) {
            synchronized (this) {
                current = definition;
                if (current == null) {
                    current = createDefinition();
                    definition = current;
                }
            }
        }
        return current;
    }

    private CopilotAgentDefinition createDefinition() {
        var component = getComponent();
        Class<?> declared = component.declaredAgent(name);
        if (declared != null) {
            if (hasAgentOptions()) {
                throw new IllegalArgumentException("copilot:agent:" + name + " refers to @CopilotAgent "
                        + declared.getName() + "; configure it on the interface, not on the endpoint");
            }
            return CopilotAgentDefinition.create(declared, component.binding());
        }
        var attributes = new CopilotAgentAttributes(name, text(instructions), text(instructionsResource),
                instructionsMode == null ? SystemMessageMode.APPEND : instructionsMode, text(systemMessageRef),
                text(model), text(reasoningEffort), text(permissionHandlerRef), names(toolRefs), names(mcpServers),
                text(provider), names(listenerRefs), names(customizerRefs), names(availableTools), names(excludedTools),
                names(excludedBuiltInAgents), builtInTools != null ? builtInTools : availableTools != null,
                toolSearch == null ? "" : toolSearch.toString(),
                toolSearchDeferThreshold == null ? 0 : toolSearchDeferThreshold, text(workingDirectory),
                names(additionalDirectories), names(hookRefs), text(tokenResolverRef), text(messageSource));
        return CopilotAgentDefinition.create(CopilotEndpointAgent.class, attributes, component.binding(settings()));
    }

    /** Overlays the endpoint's timeout and permission options on the component's settings for this agent. */
    private CopilotAgentSettings settings() {
        var base = getComponent().getAgentSettings();
        boolean permissions = allow != null || allowTools != null || allowMcpServers != null || allowAgentTools != null
                || allowReadOnly != null;
        if (responseTimeout == null && !permissions) {
            return base;
        }
        var agents = new HashMap<>(base.agents());
        var current = agents.get(name);
        var policy = permissions ? new CopilotAgentSettings.Permissions(list(allow), list(allowTools),
                list(allowMcpServers), Boolean.TRUE.equals(allowAgentTools), Boolean.TRUE.equals(allowReadOnly))
                : current == null ? null : current.permissions();
        agents.put(name, current == null
                ? new CopilotAgentSettings.Agent(null, null, responseTimeout != null ? responseTimeout
                        : CopilotAgentSettings.DEFAULT_RESPONSE_TIMEOUT, null, null, null, null, null, null,
                        null, null, policy)
                : new CopilotAgentSettings.Agent(current.model(), current.reasoningEffort(),
                        responseTimeout != null ? responseTimeout : current.responseTimeout(), current.mcpServers(),
                        current.provider(), current.availableTools(), current.excludedTools(),
                        current.excludedBuiltInAgents(), current.builtInTools(), current.workingDirectory(),
                        current.additionalDirectories(), policy, current.toolSearch(),
                        current.messageSource()));
        return new CopilotAgentSettings(agents);
    }

    private boolean hasAgentOptions() {
        return Stream.of(instructions, instructionsResource, instructionsMode, systemMessageRef, model, reasoningEffort,
                permissionHandlerRef, toolRefs, mcpServers, provider, listenerRefs, customizerRefs, availableTools,
                excludedTools, excludedBuiltInAgents, builtInTools, toolSearch, toolSearchDeferThreshold, workingDirectory,
                additionalDirectories, hookRefs, tokenResolverRef, messageSource, responseTimeout, allow, allowTools,
                allowMcpServers, allowAgentTools, allowReadOnly)
                .anyMatch(value -> value != null);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String[] names(String value) {
        return list(value).toArray(String[]::new);
    }

    private static List<String> list(String value) {
        return value == null ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).toList();
    }

    public String getPrompt() {
        return prompt;
    }

    /** Simple expression for the prompt; defaults to the message body. */
    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getConversationId() {
        return conversationId;
    }

    /**
     * Simple expression for the conversation ID; defaults to the {@code CopilotConversationId} header. Without one,
     * each exchange is a one-shot conversation whose session is deleted afterward.
     */
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getOutputClass() {
        return outputClass;
    }

    /** Requests a structured reply that is converted to this class (record, POJO, list, enum...). */
    public void setOutputClass(String outputClass) {
        this.outputClass = outputClass;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getInstructionsResource() {
        return instructionsResource;
    }

    public void setInstructionsResource(String instructionsResource) {
        this.instructionsResource = instructionsResource;
    }

    public SystemMessageMode getInstructionsMode() {
        return instructionsMode;
    }

    public void setInstructionsMode(SystemMessageMode instructionsMode) {
        this.instructionsMode = instructionsMode;
    }

    public String getSystemMessageRef() {
        return systemMessageRef;
    }

    public void setSystemMessageRef(String systemMessageRef) {
        this.systemMessageRef = systemMessageRef;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String getPermissionHandlerRef() {
        return permissionHandlerRef;
    }

    public void setPermissionHandlerRef(String permissionHandlerRef) {
        this.permissionHandlerRef = permissionHandlerRef;
    }

    public String getToolRefs() {
        return toolRefs;
    }

    /** Comma-separated tool beans: {@code copilot:tool} route names, {@code ToolDefinition}s or {@code @CopilotTool} beans. */
    public void setToolRefs(String toolRefs) {
        this.toolRefs = toolRefs;
    }

    public String getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(String mcpServers) {
        this.mcpServers = mcpServers;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getListenerRefs() {
        return listenerRefs;
    }

    public void setListenerRefs(String listenerRefs) {
        this.listenerRefs = listenerRefs;
    }

    public String getCustomizerRefs() {
        return customizerRefs;
    }

    public void setCustomizerRefs(String customizerRefs) {
        this.customizerRefs = customizerRefs;
    }

    public String getAvailableTools() {
        return availableTools;
    }

    public void setAvailableTools(String availableTools) {
        this.availableTools = availableTools;
    }

    public String getExcludedTools() {
        return excludedTools;
    }

    public void setExcludedTools(String excludedTools) {
        this.excludedTools = excludedTools;
    }

    public String getExcludedBuiltInAgents() {
        return excludedBuiltInAgents;
    }

    public void setExcludedBuiltInAgents(String excludedBuiltInAgents) {
        this.excludedBuiltInAgents = excludedBuiltInAgents;
    }

    public Boolean getBuiltInTools() {
        return builtInTools;
    }

    /**
     * Whether the agent may use the CLI's built-in tools (shell, files...). Defaults to {@code false}, or to
     * {@code true} when {@code availableTools} lists the wanted built-in tools.
     */
    public void setBuiltInTools(Boolean builtInTools) {
        this.builtInTools = builtInTools;
    }

    public Boolean getToolSearch() {
        return toolSearch;
    }

    /**
     * Whether the runtime defers MCP and external tool definitions behind its tool-search tool once the agent has
     * many tools. Unset retains the runtime default.
     */
    public void setToolSearch(Boolean toolSearch) {
        this.toolSearch = toolSearch;
    }

    public Integer getToolSearchDeferThreshold() {
        return toolSearchDeferThreshold;
    }

    /** Total tool count at which tool search defers MCP and external tools. Unset retains the runtime default. */
    public void setToolSearchDeferThreshold(Integer toolSearchDeferThreshold) {
        this.toolSearchDeferThreshold = toolSearchDeferThreshold;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getAdditionalDirectories() {
        return additionalDirectories;
    }

    public void setAdditionalDirectories(String additionalDirectories) {
        this.additionalDirectories = additionalDirectories;
    }

    public String getHookRefs() {
        return hookRefs;
    }

    public void setHookRefs(String hookRefs) {
        this.hookRefs = hookRefs;
    }

    public String getTokenResolverRef() {
        return tokenResolverRef;
    }

    public void setTokenResolverRef(String tokenResolverRef) {
        this.tokenResolverRef = tokenResolverRef;
    }

    public String getMessageSource() {
        return messageSource;
    }

    /**
     * Prompt provenance: {@code user}, {@code system}, or {@code agent-<id>}. When unset, exchanges started by a
     * {@code timer}, {@code scheduler}, {@code quartz}, or {@code cron} consumer are labeled {@code system}; other
     * prompts omit the source (user input). Exchange headers never select the source, since they may carry
     * untrusted input.
     */
    public void setMessageSource(String messageSource) {
        this.messageSource = messageSource;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    /** Maximum time to wait for a reply, such as {@code 30s} or {@code 2m}. Defaults to 60 seconds. */
    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public String getAllow() {
        return allow;
    }

    /** Comma-separated permission kinds to approve, such as {@code read,url}. */
    public void setAllow(String allow) {
        this.allow = allow;
    }

    public String getAllowTools() {
        return allowTools;
    }

    public void setAllowTools(String allowTools) {
        this.allowTools = allowTools;
    }

    public String getAllowMcpServers() {
        return allowMcpServers;
    }

    public void setAllowMcpServers(String allowMcpServers) {
        this.allowMcpServers = allowMcpServers;
    }

    public Boolean getAllowAgentTools() {
        return allowAgentTools;
    }

    /** Approves calls to this agent's own {@code toolRefs} tools. */
    public void setAllowAgentTools(Boolean allowAgentTools) {
        this.allowAgentTools = allowAgentTools;
    }

    public Boolean getAllowReadOnly() {
        return allowReadOnly;
    }

    public void setAllowReadOnly(Boolean allowReadOnly) {
        this.allowReadOnly = allowReadOnly;
    }
}
