package io.github.brunoborges.copilot.camel;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.ExtendedStartupListener;
import org.apache.camel.support.DefaultComponent;

import com.github.copilot.CopilotClient;

import io.github.brunoborges.copilot.agent.CopilotAgentTelemetry;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.github.brunoborges.copilot.agent.CopilotSessionStore;
import io.github.brunoborges.copilot.agent.internal.BindingContext;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentExecutionLimits;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentFactory;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentSettings;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionCleaner;
import io.github.brunoborges.copilot.agent.internal.CopilotSessionStorage;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotAgents;
import io.github.brunoborges.copilot.agent.internal.DefaultCopilotSessionStore;
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
    private Duration sessionTimeout = DefaultCopilotSessionStore.DEFAULT_TIMEOUT;
    private Duration sessionMaxAge;
    private Duration sessionCleanupInterval;

    private final Map<String, Class<?>> declaredAgents = new ConcurrentHashMap<>();
    private final Map<Class<?>, CopilotAgentFactory> factories = new ConcurrentHashMap<>();
    private volatile CopilotClient activeClient;
    private volatile boolean ownsClient;
    private volatile CopilotAgentInvoker invoker;
    private volatile BindingContext binding;
    private volatile CopilotSessionStore sessionStore;
    private volatile ScheduledExecutorService sessionCleanup;
    private volatile Duration activeCleanupInterval;

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
        var storage = CopilotSessionStorage.of(activeClient);
        sessionStore = new DefaultCopilotSessionStore(storage, invoker, type -> {
            var factory = factory(type);
            return factory == null ? null : factory.definition();
        }, sessionTimeout);
        if (sessionMaxAge != null) {
            var cleaner = new CopilotSessionCleaner(storage, invoker, sessionMaxAge, sessionTimeout, Clock.systemUTC());
            activeCleanupInterval = CopilotSessionCleaner.interval(sessionMaxAge, sessionCleanupInterval);
            long interval = activeCleanupInterval.toMillis();
            sessionCleanup = getCamelContext().getExecutorServiceManager()
                    .newSingleThreadScheduledExecutor(this, "CopilotSessionCleanup");
            sessionCleanup.scheduleWithFixedDelay(cleaner::sweepQuietly, interval, interval, TimeUnit.MILLISECONDS);
        }
        getCamelContext().getRegistry().bind("copilotSessionStore", CopilotSessionStore.class, sessionStore);
        registerDeclaredAgents();
    }

    @Override
    protected void doStop() throws Exception {
        var cleanup = sessionCleanup;
        sessionCleanup = null;
        activeCleanupInterval = null;
        if (cleanup != null) {
            getCamelContext().getExecutorServiceManager().shutdownNow(cleanup);
        }
        sessionStore = null;
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
        registry.bind("copilotAgents", CopilotAgents.class, new DefaultCopilotAgents(this::factory));
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

    /**
     * Lists, reads, and deletes the persisted sessions of this component's agents. {@link
     * CopilotSessionStore#sessionId(Class, String)} resolves the agent types listed in {@link #setAgentTypes(String)}.
     *
     * @throws IllegalStateException if the component is not started
     */
    public CopilotSessionStore sessionStore() {
        var current = sessionStore;
        if (current == null) {
            throw new IllegalStateException("The copilot component is not started");
        }
        return current;
    }

    /** The interval between cleanup sweeps, or {@code null} when cleanup is not scheduled. */
    Duration activeCleanupInterval() {
        return activeCleanupInterval;
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

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * Maximum time a {@link #sessionStore() session store} or cleanup call waits for the Copilot runtime, including
     * how long a deletion waits for a turn in flight on the session. Default: 2 minutes.
     */
    public void setSessionTimeout(Duration sessionTimeout) {
        if (sessionTimeout == null || !sessionTimeout.isPositive()) {
            throw new IllegalArgumentException("sessionTimeout must be positive");
        }
        this.sessionTimeout = sessionTimeout;
    }

    public Duration getSessionMaxAge() {
        return sessionMaxAge;
    }

    /**
     * Deletes agent sessions not modified for longer than this, while the component runs. Default: unset, which
     * disables cleanup.
     */
    public void setSessionMaxAge(Duration sessionMaxAge) {
        if (sessionMaxAge != null && !sessionMaxAge.isPositive()) {
            throw new IllegalArgumentException("sessionMaxAge must be positive");
        }
        this.sessionMaxAge = sessionMaxAge;
    }

    public Duration getSessionCleanupInterval() {
        return sessionCleanupInterval;
    }

    /** Time between cleanup sweeps. Default: half of {@code sessionMaxAge}, between one minute and one hour. */
    public void setSessionCleanupInterval(Duration sessionCleanupInterval) {
        if (sessionCleanupInterval != null && !sessionCleanupInterval.isPositive()) {
            throw new IllegalArgumentException("sessionCleanupInterval must be positive");
        }
        this.sessionCleanupInterval = sessionCleanupInterval;
    }
}
