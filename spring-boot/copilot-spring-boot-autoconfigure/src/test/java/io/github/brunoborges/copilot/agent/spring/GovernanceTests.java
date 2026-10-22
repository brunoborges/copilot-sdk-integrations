package io.github.brunoborges.copilot.agent.spring;

import io.github.brunoborges.copilot.agent.*;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.AgentStopHookInput;
import com.github.copilot.rpc.AgentStopHookOutput;
import com.github.copilot.rpc.McpHttpServerConfig;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.PostToolUseHookInput;
import com.github.copilot.rpc.PostToolUseHookOutput;
import com.github.copilot.rpc.PreToolUseHookInput;
import com.github.copilot.rpc.PreToolUseHookOutput;
import com.github.copilot.rpc.ResumeSessionConfig;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionHooks;
import com.github.copilot.rpc.SessionMetadata;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolNamespace;
import com.github.copilot.rpc.UserPromptSubmittedHookInput;
import com.github.copilot.rpc.UserPromptSubmittedHookOutput;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.autoconfigure.internal.CopilotAgentFactoryBean;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentGovernance;
import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceTests {

    private final CopilotClient client = mock(CopilotClient.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final StandardEnvironment environment = new StandardEnvironment();
    private final CopilotAgentsProperties none = new CopilotAgentsProperties(Map.of());
    private final List<String> calls = new ArrayList<>();

    // --- built-in tool governance ---

    @Test
    void toolGovernanceReachesCreateAndResumeAndKeepsAgentToolsUsable() throws Exception {
        registerToolAndMcp();
        var definition = definition(GovernedAgent.class, none);
        assertThat(definition.governance().availableTools()).containsExactly("view", "builtin:grep", "custom:*", "mcp:*");
        assertThat(definition.governance().excludedTools()).isNull();

        var configs = run(GovernedAgent.class, none);
        SessionConfig created = (SessionConfig) configs.get(0);
        ResumeSessionConfig resumed = (ResumeSessionConfig) configs.get(1);
        assertThat(created.getAvailableTools()).containsExactly("view", "builtin:grep", "custom:*", "mcp:*");
        assertThat(resumed.getAvailableTools()).isEqualTo(created.getAvailableTools());
        assertThat(created.getExcludedBuiltInAgents()).containsExactly("explore");
        assertThat(resumed.getExcludedBuiltInAgents()).containsExactly("explore");
        assertThat(created.getWorkingDirectory()).isEqualTo("/srv/work");
        assertThat(resumed.getWorkingDirectory()).isEqualTo("/srv/work");
        assertThat(created.getAdditionalDirectories()).containsExactly("/srv/shared");
        assertThat(resumed.getAdditionalDirectories()).containsExactly("/srv/shared");
        assertThat(created.getExcludedTools()).isNull();
        assertThat(created.getHooks()).isNull();
    }

    @Test
    void allowListsThatHideTheAgentsOwnToolSourcesFailStartup() {
        registerToolAndMcp();
        var noCustom = agentSettings("governedAgent", List.of("view", "mcp:*"), null, null, null, null, null);
        assertThatThrownBy(() -> definition(GovernedAgent.class, noCustom))
                .hasMessageContaining("hides every tool bean").hasMessageContaining("custom:*");
        var noMcp = agentSettings("governedAgent", List.of("view", "custom:echo"), null, null, null, null, null);
        assertThatThrownBy(() -> definition(GovernedAgent.class, noMcp))
                .hasMessageContaining("hides every MCP server tool").hasMessageContaining("mcp:*");
    }

    @Test
    void explicitSourcePatternsAreNotWidened() {
        registerToolAndMcp();
        var settings = agentSettings("governedAgent", List.of("custom:echo", "mcp:docs-search"), null, null, null, null, null);
        assertThat(definition(GovernedAgent.class, settings).governance().availableTools())
                .containsExactly("custom:echo", "mcp:docs-search");
        var noTools = definition(AllowListOnlyAgent.class, none);
        assertThat(noTools.governance().availableTools()).containsExactly("view");
    }

    @Test
    void propertiesOverrideAnnotationToolGovernance() {
        registerToolAndMcp();
        var settings = agentSettings("governedAgent", List.of(), List.of("bash"), List.of(), null, "/override",
                List.of("/a", "/b"));
        var governance = definition(GovernedAgent.class, settings).governance();
        assertThat(governance.availableTools()).isNull();
        assertThat(governance.excludedTools()).containsExactly("bash");
        assertThat(governance.excludedBuiltInAgents()).isNull();
        assertThat(governance.workingDirectory()).isEqualTo("/override");
        assertThat(governance.additionalDirectories()).containsExactly("/a", "/b");

        var cleared = agentSettings("governedAgent", null, null, null, null, "", null);
        assertThat(definition(GovernedAgent.class, cleared).governance().workingDirectory()).isNull();
    }

    @Test
    void builtInToolsCanBeDisabledButNotCombinedWithAnAllowList() throws Exception {
        assertThat(definition(NoBuiltInsAgent.class, none).governance().excludedTools()).containsExactly("bash", "builtin:*");
        var configs = run(NoBuiltInsAgent.class, none);
        assertThat(((SessionConfig) configs.get(0)).getExcludedTools()).containsExactly("bash", "builtin:*");
        assertThat(((ResumeSessionConfig) configs.get(1)).getExcludedTools()).containsExactly("bash", "builtin:*");

        var enabled = agentSettings("noBuiltInsAgent", null, null, null, true, null, null);
        assertThat(definition(NoBuiltInsAgent.class, enabled).governance().excludedTools()).containsExactly("bash");
        var disabled = agentSettings("allowListOnlyAgent", null, null, null, false, null, null);
        assertThatThrownBy(() -> definition(AllowListOnlyAgent.class, disabled))
                .hasMessageContaining("built-in-tools=false cannot be combined with available-tools");
        var both = agentSettings("allowListOnlyAgent", null, List.of("bash"), null, null, null, null);
        assertThatThrownBy(() -> definition(AllowListOnlyAgent.class, both))
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void blankGovernancePropertiesAreRejected() {
        assertThatThrownBy(() -> agentSettings("a", List.of(" "), null, null, null, null, null))
                .hasMessageContaining("available-tools");
        assertThatThrownBy(() -> agentSettings("a", null, null, null, null, " ", null))
                .hasMessageContaining("working-directory");
        assertThatThrownBy(() -> new CopilotAgentsProperties.Permissions(List.of(""), null, null, false, false))
                .hasMessageContaining("permissions.allow");
    }

    // --- permission policies ---

    @Test
    void permissionPoliciesApproveOnlyMatchingRequests() {
        assertThat(approved(CopilotPermissionPolicies.denyAll(), request("read"))).isFalse();
        assertThat(CopilotPermissionPolicies.allowAll()).isSameAs(PermissionHandler.APPROVE_ALL);

        var kinds = CopilotPermissionPolicies.allowKinds("read", "url");
        assertThat(approved(kinds, request("read"))).isTrue();
        assertThat(approved(kinds, request("url"))).isTrue();
        assertThat(approved(kinds, request("shell"))).isFalse();
        assertThat(approved(kinds, request(null))).isFalse();

        var tools = CopilotPermissionPolicies.allowTools("echo", "search");
        assertThat(approved(tools, request("custom-tool", "toolName", "echo"))).isTrue();
        assertThat(approved(tools, request("mcp", "toolName", "search", "serverName", "docs"))).isTrue();
        assertThat(approved(tools, request("custom-tool", "toolName", "other"))).isFalse();
        assertThat(approved(tools, request("shell", "toolName", "echo"))).isFalse();

        var servers = CopilotPermissionPolicies.allowMcpServers("docs");
        assertThat(approved(servers, request("mcp", "toolName", "anything", "serverName", "docs"))).isTrue();
        assertThat(approved(servers, request("mcp", "toolName", "anything", "serverName", "other"))).isFalse();

        var readOnly = CopilotPermissionPolicies.allowReadOnly();
        assertThat(approved(readOnly, request("read"))).isTrue();
        assertThat(approved(readOnly, request("mcp", "readOnly", true))).isTrue();
        assertThat(approved(readOnly, request("mcp", "readOnly", false))).isFalse();
        assertThat(approved(readOnly, request("write"))).isFalse();

        var custom = CopilotPermissionPolicies.allowCustomTools(List.of(
                ToolDefinition.create("echo", "Echo", Map.of(), invocation -> null),
                new ToolDefinition("lookup", "Lookup", Map.of(), invocation -> null, null, null, null, null, null,
                        new ToolNamespace("orders"))));
        assertThat(approved(custom, request("custom-tool", "toolName", "echo"))).isTrue();
        assertThat(approved(custom, request("custom-tool", "toolName", "orders.lookup"))).isTrue();
        assertThat(approved(custom, request("mcp", "toolName", "echo"))).isFalse();

        var rejection = CopilotPermissionPolicies.denyAll().handle(request("shell"), null).join();
        assertThat(rejection.getKind()).isEqualTo("reject");
        assertThat(rejection.getFeedback()).contains("shell");
    }

    @Test
    void managedApprovalRequestsAreNeverAutoApprovedByPolicies() {
        var request = request("read");
        request.setManagedApprovalRequired(true);
        assertThat(approved(CopilotPermissionPolicies.allowKinds("read"), request)).isFalse();
        assertThat(approved(CopilotPermissionPolicies.anyOf(CopilotPermissionPolicies.allowReadOnly()), request)).isFalse();
    }

    @Test
    void anyOfReturnsFirstApprovalAndTreatsFailuresAsDenials() {
        var order = new ArrayList<String>();
        PermissionHandler failing = (request, invocation) -> {
            order.add("failing");
            throw new IllegalStateException("boom");
        };
        PermissionHandler failedFuture = (request, invocation) -> {
            order.add("failedFuture");
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        };
        PermissionHandler approving = (request, invocation) -> {
            order.add("approving");
            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
        };
        PermissionHandler never = (request, invocation) -> {
            order.add("never");
            return CompletableFuture.completedFuture(PermissionRequestResult.approveOnce());
        };
        var composed = CopilotPermissionPolicies.anyOf(CopilotPermissionPolicies.denyAll(), failing, failedFuture,
                approving, never);
        assertThat(approved(composed, request("shell"))).isTrue();
        assertThat(order).containsExactly("failing", "failedFuture", "approving");

        var denied = CopilotPermissionPolicies.anyOf(CopilotPermissionPolicies.denyAll(), failing).handle(request("shell"), null).join();
        assertThat(denied.getKind()).isEqualTo("reject");
        assertThat(approved(CopilotPermissionPolicies.anyOf(List.of()), request("read"))).isFalse();
        assertThat(approved(CopilotPermissionPolicies.anyOf(CopilotPermissionPolicies.allowKinds("url"),
                CopilotPermissionPolicies.allowKinds("read")), request("read"))).isTrue();
        assertThat(CopilotPermissionPolicies.isApproval(PermissionRequestResult.approveReadOnlyForSession(List.of("/")))).isTrue();
        assertThat(CopilotPermissionPolicies.isApproval(PermissionRequestResult.noResult())).isFalse();
        assertThat(CopilotPermissionPolicies.isApproval(null)).isFalse();
    }

    @Test
    void propertyPermissionsBuildAPolicyAndDenyByDefaultIsKept() {
        registerToolAndMcp();
        assertThat(definition(GovernedAgent.class, none).permissions().handle(request("read"), null).join().getFeedback())
                .isEqualTo("No permission handler was configured for this agent.");

        var permissions = new CopilotAgentsProperties.Permissions(List.of("url"), List.of("search"), List.of("docs"), true, true);
        var settings = new CopilotAgentsProperties(Map.of("governedAgent", new CopilotAgentsProperties.Agent(null, null,
                Duration.ofSeconds(60), null, null, null, null, null, null, null, null, permissions)));
        var policy = definition(GovernedAgent.class, settings).permissions();
        assertThat(approved(policy, request("url"))).isTrue();
        assertThat(approved(policy, request("read"))).isTrue();
        assertThat(approved(policy, request("custom-tool", "toolName", "echo"))).isTrue();
        assertThat(approved(policy, request("mcp", "toolName", "search", "serverName", "x"))).isTrue();
        assertThat(approved(policy, request("mcp", "toolName", "x", "serverName", "docs"))).isTrue();
        assertThat(approved(policy, request("shell"))).isFalse();
        assertThat(approved(policy, request("custom-tool", "toolName", "unknown"))).isFalse();

        var empty = new CopilotAgentsProperties(Map.of("governedAgent", new CopilotAgentsProperties.Agent(null, null,
                Duration.ofSeconds(60), null, null, null, null, null, null, null, null,
                new CopilotAgentsProperties.Permissions(null, null, null, false, false))));
        assertThat(approved(definition(GovernedAgent.class, empty).permissions(), request("read"))).isFalse();

        beans.registerSingleton("permissions", PermissionHandler.APPROVE_ALL);
        var conflicting = new CopilotAgentsProperties(Map.of("handlerAgent", new CopilotAgentsProperties.Agent(null, null,
                Duration.ofSeconds(60), null, null, null, null, null, null, null, null, permissions)));
        assertThatThrownBy(() -> definition(HandlerAgent.class, conflicting))
                .hasMessageContaining("cannot be combined with a permissionHandlerRef bean");
        assertThat(definition(HandlerAgent.class, none).permissions()).isSameAs(PermissionHandler.APPROVE_ALL);
    }

    // --- hooks ---

    @Test
    void hooksComposeInOrderOnCreateAndResumeWithGlobalHooksFirst() throws Exception {
        beans.registerSingleton("late", new LateGlobal(calls));
        beans.registerSingleton("early", new EarlyGlobal(calls));
        beans.registerSingleton("audit", new Recording("audit", calls, null));
        beans.registerSingleton("guard", new Recording("guard", calls, PreToolUseHookOutput.allow()));
        beans.registerSingleton("notGlobal", new Recording("notGlobal", calls, null));

        var definition = definition(HookedAgent.class, none);
        assertThat(definition.governance().hooks()).extracting(Object::getClass).extracting(Class::getSimpleName)
                .containsExactly("EarlyGlobal", "LateGlobal", "Recording", "Recording");

        var configs = run(HookedAgent.class, none);
        SessionHooks created = ((SessionConfig) configs.get(0)).getHooks();
        SessionHooks resumed = ((ResumeSessionConfig) configs.get(1)).getHooks();
        assertThat(created).isNotNull();
        assertThat(resumed).isNotNull();

        var output = created.getOnPreToolUse().handle(preToolUse("bash"), null).join();
        assertThat(output.permissionDecision()).isEqualTo("allow");
        assertThat(calls).containsExactly("early:bash:hookedAgent", "late:bash", "audit:bash", "guard:bash");
        calls.clear();
        resumed.getOnPreToolUse().handle(preToolUse("view"), null).join();
        assertThat(calls).containsExactly("early:view:hookedAgent", "late:view", "audit:view", "guard:view");

        var missing = new DefaultListableBeanFactory();
        missing.registerSingleton("audit", new Recording("audit", calls, null));
        missing.registerSingleton("guard", "not a hook");
        assertThatThrownBy(() -> SpringTestSupport.definition(HookedAgent.class, missing, environment,
                new DefaultResourceLoader(), none))
                .hasMessageContaining("hookRefs references bean 'guard' of type java.lang.String")
                .hasMessageContaining("CopilotAgentHooks");
    }

    @Test
    void preToolUseDenyWinsAskBeatsAllowAndArgumentsFlow() {
        var context = new CopilotAgentContext("agent", "c", "s");
        var args = JsonNodeFactory.instance.objectNode().put("path", "/safe");
        CopilotAgentHooks allow = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                calls.add("allow");
                return new PreToolUseHookOutput("allow", null, args, "first", null);
            }
        };
        CopilotAgentHooks ask = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                calls.add("ask:" + input.getToolArgs().get("path").asText());
                return new PreToolUseHookOutput("ask", "confirm", null, "second", true);
            }
        };
        CopilotAgentHooks deny = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                calls.add("deny");
                return PreToolUseHookOutput.deny("forbidden");
            }
        };
        CopilotAgentHooks after = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                calls.add("after");
                return PreToolUseHookOutput.allow();
            }
        };

        var askResult = hooks(context, allow, ask, after).getOnPreToolUse().handle(preToolUse("write"), null).join();
        assertThat(askResult.permissionDecision()).isEqualTo("ask");
        assertThat(askResult.permissionDecisionReason()).isEqualTo("confirm");
        assertThat(askResult.modifiedArgs()).isEqualTo(args);
        assertThat(askResult.additionalContext()).isEqualTo("first\n\nsecond");
        assertThat(askResult.suppressOutput()).isTrue();
        assertThat(calls).containsExactly("allow", "ask:/safe", "after");

        calls.clear();
        var denied = hooks(context, allow, deny, after).getOnPreToolUse().handle(preToolUse("write"), null).join();
        assertThat(denied.permissionDecision()).isEqualTo("deny");
        assertThat(denied.permissionDecisionReason()).isEqualTo("forbidden");
        assertThat(calls).containsExactly("allow", "deny");

        assertThat(hooks(context, new CopilotAgentHooks() { }).getOnPreToolUse().handle(preToolUse("x"), null).join())
                .isNull();
    }

    @Test
    void failingGateHooksFailClosedAndOtherHookFailuresAreIgnored() {
        var context = new CopilotAgentContext("agent", "c", "s");
        CopilotAgentHooks broken = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                throw new IllegalStateException("broken");
            }

            @Override
            public UserPromptSubmittedHookOutput onUserPromptSubmitted(CopilotAgentContext ctx,
                    UserPromptSubmittedHookInput input) {
                throw new IllegalStateException("broken");
            }

            @Override
            public PostToolUseHookOutput onPostToolUse(CopilotAgentContext ctx, PostToolUseHookInput input) {
                throw new IllegalStateException("broken");
            }

            @Override
            public AgentStopHookOutput onAgentStop(CopilotAgentContext ctx, AgentStopHookInput input) {
                throw new IllegalStateException("broken");
            }
        };
        CopilotAgentHooks permissive = new CopilotAgentHooks() {
            @Override
            public PreToolUseHookOutput onPreToolUse(CopilotAgentContext ctx, PreToolUseHookInput input) {
                return PreToolUseHookOutput.allow();
            }

            @Override
            public PostToolUseHookOutput onPostToolUse(CopilotAgentContext ctx, PostToolUseHookInput input) {
                return new PostToolUseHookOutput(null, "audited", null);
            }
        };
        var chain = hooks(context, permissive, broken, permissive);
        assertThat(chain.getOnPreToolUse().handle(preToolUse("bash"), null).join().permissionDecision()).isEqualTo("deny");
        var prompt = chain.getOnUserPromptSubmitted()
                .handle(new UserPromptSubmittedHookInput("s", 0, "/", "hello"), null).join();
        assertThat(prompt.decision()).isEqualTo("block");
        var post = chain.getOnPostToolUse().handle(new PostToolUseHookInput().setToolName("bash"), null).join();
        assertThat(post.additionalContext()).isEqualTo("audited\n\naudited");
        assertThat(chain.getOnAgentStop().handle(new AgentStopHookInput(), null).join()).isNull();
    }

    @Test
    void userPromptHooksChainModificationsAndBlockWins() {
        var context = new CopilotAgentContext("agent", "c", "s");
        CopilotAgentHooks redact = new CopilotAgentHooks() {
            @Override
            public UserPromptSubmittedHookOutput onUserPromptSubmitted(CopilotAgentContext ctx,
                    UserPromptSubmittedHookInput input) {
                return new UserPromptSubmittedHookOutput(input.prompt().replace("secret", "***"), null, null);
            }
        };
        CopilotAgentHooks shout = new CopilotAgentHooks() {
            @Override
            public UserPromptSubmittedHookOutput onUserPromptSubmitted(CopilotAgentContext ctx,
                    UserPromptSubmittedHookInput input) {
                return new UserPromptSubmittedHookOutput(input.prompt().toUpperCase(), "ctx", null);
            }
        };
        CopilotAgentHooks block = new CopilotAgentHooks() {
            @Override
            public UserPromptSubmittedHookOutput onUserPromptSubmitted(CopilotAgentContext ctx,
                    UserPromptSubmittedHookInput input) {
                return input.prompt().contains("***") ? UserPromptSubmittedHookOutput.block("redacted") : null;
            }
        };
        var input = new UserPromptSubmittedHookInput("s", 0, "/", "my secret");
        var modified = hooks(context, redact, shout).getOnUserPromptSubmitted().handle(input, null).join();
        assertThat(modified.modifiedPrompt()).isEqualTo("MY ***");
        assertThat(modified.additionalContext()).isEqualTo("ctx");
        var blocked = hooks(context, redact, block, shout).getOnUserPromptSubmitted().handle(input, null).join();
        assertThat(blocked.decision()).isEqualTo("block");
        assertThat(blocked.reason()).isEqualTo("redacted");
    }

    @Test
    void agentStopBlockWins() {
        var context = new CopilotAgentContext("agent", "c", "s");
        CopilotAgentHooks allow = new CopilotAgentHooks() {
            @Override
            public AgentStopHookOutput onAgentStop(CopilotAgentContext ctx, AgentStopHookInput input) {
                return new AgentStopHookOutput();
            }
        };
        CopilotAgentHooks block = new CopilotAgentHooks() {
            @Override
            public AgentStopHookOutput onAgentStop(CopilotAgentContext ctx, AgentStopHookInput input) {
                return new AgentStopHookOutput().setDecision("block").setReason("continue");
            }
        };
        assertThat(hooks(context, allow, block, allow).getOnAgentStop().handle(new AgentStopHookInput(), null).join()
                .getReason()).isEqualTo("continue");
    }

    // --- helpers ---

    private static SessionHooks hooks(CopilotAgentContext context, CopilotAgentHooks... hooks) {
        return CopilotAgentGovernance.sessionHooks(context, hooks);
    }

    private void registerToolAndMcp() {
        beans.registerSingleton("echo", ToolDefinition.create("echo", "Echo", Map.of(), invocation -> null));
        beans.registerSingleton("docs", new McpHttpServerConfig().setUrl("https://example.com/mcp"));
    }

    private List<Object> run(Class<?> type, CopilotAgentsProperties settings) throws Exception {
        var session = mock(CopilotSession.class);
        when(session.on(any())).thenReturn(mock(Closeable.class));
        var event = new AssistantMessageEvent();
        var data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(data.content()).thenReturn("ok");
        event.setData(data);
        when(session.sendAndWait(any(), anyLong())).thenReturn(CompletableFuture.completedFuture(event));
        when(client.getSessionMetadata(anyString())).thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.completedFuture(new SessionMetadata()));
        when(client.createSession(any())).thenAnswer(invocation -> {
            when(session.getSessionId()).thenReturn(invocation.<SessionConfig>getArgument(0).getSessionId());
            return CompletableFuture.completedFuture(session);
        });
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(session));
        try (var invoker = new CopilotAgentInvoker(client)) {
            var factory = new CopilotAgentFactoryBean(type, invoker, beans, environment, new DefaultResourceLoader(), settings);
            factory.afterPropertiesSet();
            var method = type.getMethods()[0];
            for (String message : List.of("first", "second")) {
                @SuppressWarnings("unchecked")
                var reply = (CompletableFuture<String>) method.invoke(factory.getObject(), "id", message);
                assertThat(reply.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            }
        }
        ArgumentCaptor<SessionConfig> created = ArgumentCaptor.captor();
        ArgumentCaptor<ResumeSessionConfig> resumed = ArgumentCaptor.captor();
        verify(client).createSession(created.capture());
        verify(client).resumeSession(anyString(), resumed.capture());
        return List.of(created.getValue(), resumed.getValue());
    }

    private CopilotAgentDefinition definition(Class<?> type, CopilotAgentsProperties settings) {
        return SpringTestSupport.definition(type, beans, environment, new DefaultResourceLoader(), settings);
    }

    private static CopilotAgentsProperties agentSettings(String name, List<String> available, List<String> excluded,
            List<String> agents, Boolean builtIns, String directory, List<String> directories) {
        return new CopilotAgentsProperties(Map.of(name, new CopilotAgentsProperties.Agent(null, null,
                Duration.ofSeconds(60), null, null, available, excluded, agents, builtIns, directory, directories,
                null)));
    }

    private static boolean approved(PermissionHandler handler, PermissionRequest request) {
        return CopilotPermissionPolicies.isApproval(handler.handle(request, null).join());
    }

    private static PermissionRequest request(String kind, Object... data) {
        var values = new java.util.LinkedHashMap<String, Object>();
        if (kind != null) {
            values.put("kind", kind);
        }
        for (int i = 0; i < data.length; i += 2) {
            values.put((String) data[i], data[i + 1]);
        }
        return PermissionRequest.fromJsonValue(values);
    }

    private static PreToolUseHookInput preToolUse(String tool) {
        return new PreToolUseHookInput().setToolName(tool).setToolArgs(JsonNodeFactory.instance.objectNode());
    }

    static class Recording implements CopilotAgentHooks {
        private final String name;
        private final List<String> calls;
        private final PreToolUseHookOutput output;

        Recording(String name, List<String> calls, PreToolUseHookOutput output) {
            this.name = name;
            this.calls = calls;
            this.output = output;
        }

        @Override
        public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
            calls.add(name + ":" + input.getToolName());
            return output;
        }
    }

    @GlobalCopilotAgentHooks
    @Order(1)
    static class EarlyGlobal implements CopilotAgentHooks {
        private final List<String> calls;

        EarlyGlobal(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
            calls.add("early:" + input.getToolName() + ":" + context.agentName());
            return null;
        }
    }

    @GlobalCopilotAgentHooks
    @Order(2)
    static class LateGlobal implements CopilotAgentHooks {
        private final List<String> calls;

        LateGlobal(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public PreToolUseHookOutput onPreToolUse(CopilotAgentContext context, PreToolUseHookInput input) {
            calls.add("late:" + input.getToolName());
            return null;
        }
    }

    @CopilotAgent(toolRefs = "echo", mcpServers = "docs", availableTools = {"view", "builtin:grep", "custom:*", "mcp:*"},
            excludedBuiltInAgents = "explore", workingDirectory = "/srv/work", additionalDirectories = "/srv/shared")
    public interface GovernedAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(availableTools = "view")
    public interface AllowListOnlyAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(builtInTools = false, excludedTools = "bash")
    public interface NoBuiltInsAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(permissionHandlerRef = "permissions")
    public interface HandlerAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent(hookRefs = {"audit", "guard", "early"})
    public interface HookedAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }

    @CopilotAgent
    public interface HooklessAgent {
        CompletableFuture<String> answer(@ConversationId String id, @UserMessage String message);
    }
}
