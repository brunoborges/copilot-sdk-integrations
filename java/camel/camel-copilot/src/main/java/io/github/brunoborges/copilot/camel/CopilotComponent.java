package io.github.brunoborges.copilot.camel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.ExtendedStartupListener;
import org.apache.camel.support.DefaultComponent;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotLoops;
import io.github.brunoborges.copilot.agent.internal.BindingContext;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentExecutionLimits;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactory;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentSettings;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotLoops;
import io.github.brunoborges.copilot.agent.internal.ResourceLookup;

/**
 * The {@code copilot:} component. It exposes four endpoint kinds:
 * <ul>
 * <li>{@code copilot:agent:<name>} – producer that sends the message to a Copilot agent and replies with its answer;</li>
 * <li>{@code copilot:tool:<name>} – consumer that implements a Copilot tool as a route;</li>
 * <li>{@code copilot:permission:<name>} – consumer that decides permission requests as a route;</li>
 * <li>{@code copilot:events:<name>} – consumer that receives agent session events.</li>
 * </ul>
 * Tool, permission and event endpoints are registered in the Camel registry under {@code <name>}, so agents reference
 * them with {@code toolRefs}, {@code permissionHandlerRef} and {@code listenerRefs} like any other bean.
 */
public class CopilotComponent extends DefaultComponent {

    static final String CONFIG_PREFIX = "camel.component.copilot";
    private static final Logger log = Logger.getLogger(CopilotComponent.class.getName());

    private CopilotClient client;
    private CopilotAgentTelemetry telemetry;
    private CopilotAgentSettings agentSettings;
    private String agentTypes;
    private int maxConcurrentInvocations = CopilotAgentExecutionLimits.DEFAULT.maxConcurrentInvocations();
    private int maxOutstandingInvocations = CopilotAgentExecutionLimits.DEFAULT.maxOutstandingInvocations();

    private final Map<String, Class<?>> declaredAgents = new ConcurrentHashMap<>();
    private final Map<Class<?>, CopilotAgentFactory> factories = new ConcurrentHashMap<>();
    private volatile CopilotClient activeClient;
    private volatile boolean ownsClient;
    private volatile CopilotAgentInvoker invoker;
    private volatile BindingContext binding;

    public CopilotComponent() {
    }

    public CopilotComponent(CamelContext context) {
        super(context);
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        int separator = remaining.indexOf(':');
        String kind = separator < 0 ? remaining : remaining.substring(0, separator);
        String name = separator < 0 ? "" : remaining.substring(separator + 1);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Copilot endpoint must name its " + kind + ": copilot:" + kind + ":<name>");
        }
        Endpoint endpoint = switch (kind) {
            case "agent" -> new CopilotAgentEndpoint(uri, this, name);
            case "tool" -> new CopilotToolEndpoint(uri, this, name);
            case "permission" -> new CopilotPermissionEndpoint(uri, this, name);
            case "events" -> new CopilotEventsEndpoint(uri, this, name);
            default -> throw new IllegalArgumentException("Unknown Copilot endpoint kind '" + kind
                    + "'; expected agent, tool, permission or events");
        };
        setProperties(endpoint, parameters);
        return endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        var registry = getCamelContext().getRegistry();
        CopilotClient selected = client != null ? client : registry.findSingleByType(CopilotClient.class);
        ownsClient = selected == null;
        activeClient = selected != null ? selected : new CopilotClient();
        CopilotAgentTelemetry selectedTelemetry = telemetry != null ? telemetry
                : registry.findSingleByType(CopilotAgentTelemetry.class);
        invoker = new CopilotAgentInvoker(activeClient,
                selectedTelemetry != null ? selectedTelemetry : CopilotAgentTelemetry.NOOP,
                new CopilotAgentExecutionLimits(maxConcurrentInvocations, maxOutstandingInvocations));
        registerDeclaredAgents();
    }

    @Override
    protected void doStop() throws Exception {
        var current = invoker;
        invoker = null;
        if (current != null) {
            current.close();
        }
        if (ownsClient && activeClient != null) {
            try {
                activeClient.close();
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Failed to close the Copilot client", ex);
            }
        }
        activeClient = null;
        factories.clear();
        declaredAgents.clear();
        binding = null;
        super.doStop();
    }

    private void registerDeclaredAgents() throws Exception {
        if (agentTypes == null || agentTypes.isBlank()) {
            return;
        }
        for (String typeName : agentTypes.split(",")) {
            Class<?> type = getCamelContext().getClassResolver().resolveMandatoryClass(typeName.trim());
            String name = CopilotAgentDefinition.name(type, binding().environment());
            if (declaredAgents.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("Two Copilot agent types are named '" + name + "'");
            }
        }
        var registry = getCamelContext().getRegistry();
        var agents = new DefaultCopilotAgents(this::factory);
        registry.bind("copilotAgents", CopilotAgents.class, agents);
        registry.bind("copilotLoops", CopilotLoops.class, new DefaultCopilotLoops(agents));
        // Agents resolve toolRefs to copilot:tool routes, so bind proxies only once every route has been created.
        getCamelContext().addStartupListener(new ExtendedStartupListener() {
            @Override
            public void onCamelContextStarted(CamelContext context, boolean alreadyStarted) {
            }

            @Override
            public void onCamelContextFullyStarted(CamelContext context, boolean alreadyStarted) {
                declaredAgents.forEach((name, type) -> {
                    var factory = factory(type);
                    if (!factory.boundDefinition() && registry.lookupByName(name) == null) {
                        registry.bind(name, type, factory.getObject());
                    }
                });
            }
        });
    }

    CopilotAgentFactory factory(Class<?> type) {
        if (type == null || !declaredAgents.containsValue(type)) {
            return null;
        }
        return factories.computeIfAbsent(type, candidate -> new CopilotAgentFactory(candidate, invoker(), binding()));
    }

    Class<?> declaredAgent(String name) {
        return declaredAgents.get(name);
    }

    CopilotAgentInvoker invoker() {
        var current = invoker;
        if (current == null) {
            throw new IllegalStateException("The copilot component is not started");
        }
        return current;
    }

    CopilotClient activeClient() {
        return activeClient;
    }

    BindingContext binding() {
        return binding(getAgentSettings());
    }

    BindingContext binding(CopilotAgentSettings settings) {
        if (settings == getAgentSettings() && binding != null) {
            return binding;
        }
        var context = getCamelContext();
        var created = new BindingContext(new CamelRegistryBeanLookup(context), context::resolvePropertyPlaceholders,
                ResourceLookup.classpath(context.getApplicationContextClassLoader()), settings, name -> null,
                name -> null, CONFIG_PREFIX);
        if (settings == getAgentSettings()) {
            binding = created;
        }
        return created;
    }

    public CopilotClient getClient() {
        return client;
    }

    /** The Copilot client to use. Defaults to the single {@link CopilotClient} in the registry, or a new one. */
    public void setClient(CopilotClient client) {
        this.client = client;
    }

    public CopilotAgentTelemetry getTelemetry() {
        return telemetry;
    }

    /** Agent telemetry. Defaults to the single {@link CopilotAgentTelemetry} in the registry, or none. */
    public void setTelemetry(CopilotAgentTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public CopilotAgentSettings getAgentSettings() {
        if (agentSettings == null) {
            var registered = getCamelContext() == null ? null
                    : getCamelContext().getRegistry().findSingleByType(CopilotAgentSettings.class);
            agentSettings = registered != null ? registered : new CopilotAgentSettings(null);
        }
        return agentSettings;
    }

    /** Per-agent overrides (model, timeout, permissions...) keyed by agent name. */
    public void setAgentSettings(CopilotAgentSettings agentSettings) {
        this.agentSettings = agentSettings;
    }

    public String getAgentTypes() {
        return agentTypes;
    }

    /**
     * Comma-separated {@code @CopilotAgent} interfaces to register. Explicit-ID agents are bound in the registry under
     * their agent name (for {@code bean:} calls) and every listed agent can be invoked with {@code copilot:agent:<name>}.
     */
    public void setAgentTypes(String agentTypes) {
        this.agentTypes = agentTypes;
    }

    public int getMaxConcurrentInvocations() {
        return maxConcurrentInvocations;
    }

    public void setMaxConcurrentInvocations(int maxConcurrentInvocations) {
        this.maxConcurrentInvocations = maxConcurrentInvocations;
    }

    public int getMaxOutstandingInvocations() {
        return maxOutstandingInvocations;
    }

    public void setMaxOutstandingInvocations(int maxOutstandingInvocations) {
        this.maxOutstandingInvocations = maxOutstandingInvocations;
    }
}
