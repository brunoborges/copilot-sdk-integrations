package com.microsoft.copilot.integrations.camel;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;

import com.github.copilot.generated.SessionEvent;

import com.microsoft.copilot.integrations.core.CopilotAgentContext;
import com.microsoft.copilot.integrations.core.CopilotAgentListener;

/**
 * {@code copilot:events:<name>} – receives the session events of every agent that lists {@code <name>} in
 * {@code listenerRefs}. Events are routed in order on one thread, off the Copilot event thread; when more than
 * {@code queueSize} events are waiting, new events are dropped with a warning.
 */
public class CopilotEventsEndpoint extends DefaultEndpoint {

    private static final Logger log = Logger.getLogger(CopilotEventsEndpoint.class.getName());

    private final String name;
    private String types;
    private int queueSize = 1000;

    private List<Pattern> filters = List.of();
    private volatile Delivery delivery;

    CopilotEventsEndpoint(String uri, CopilotComponent component, String name) {
        super(uri, component);
        this.name = name;
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();
        if (types != null) {
            filters = Arrays.stream(types.split(",")).map(String::trim).filter(type -> !type.isEmpty())
                    .map(CopilotEventsEndpoint::glob).toList();
        }
        getCamelContext().getRegistry().bind(name, CopilotAgentListener.class, (CopilotAgentListener) this::onEvent);
    }

    private static Pattern glob(String type) {
        var regex = new StringBuilder();
        for (String part : type.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    private void onEvent(CopilotAgentContext context, SessionEvent event) {
        var current = delivery;
        if (current == null || !(filters.isEmpty()
                || filters.stream().anyMatch(filter -> filter.matcher(event.getType()).matches()))) {
            return;
        }
        current.submit(context, event);
    }

    @Override
    public Producer createProducer() {
        throw new UnsupportedOperationException("copilot:events is a consumer; agents publish to it via listenerRefs");
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        var created = new Delivery(processor);
        configureConsumer(created);
        return created;
    }

    private final class Delivery extends DefaultConsumer {

        private final AtomicLong dropped = new AtomicLong();
        private ExecutorService executor;

        Delivery(Processor processor) {
            super(CopilotEventsEndpoint.this, processor);
        }

        @Override
        protected void doStart() throws Exception {
            super.doStart();
            if (delivery != null) {
                throw new IllegalStateException("copilot:events:" + name + " already has a consumer");
            }
            executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                    Thread.ofPlatform().name("copilot-events-" + name).daemon().factory());
            delivery = this;
        }

        @Override
        protected void doStop() throws Exception {
            if (delivery == this) {
                delivery = null;
            }
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            super.doStop();
        }

        void submit(CopilotAgentContext context, SessionEvent event) {
            try {
                executor.execute(() -> route(context, event));
            } catch (RejectedExecutionException ex) {
                long count = dropped.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    log.warning("copilot:events:" + name + " is falling behind; dropped " + count + " events");
                }
            }
        }

        private void route(CopilotAgentContext context, SessionEvent event) {
            RouteDispatch.dispatch(this, exchange -> {
                var message = exchange.getMessage();
                message.setBody(event);
                message.setHeader(CopilotConstants.EVENT_TYPE, event.getType());
                message.setHeader(CopilotConstants.AGENT_NAME, context.agentName());
                message.setHeader(CopilotConstants.CONVERSATION_ID, context.conversationId());
                message.setHeader(CopilotConstants.SESSION_ID, context.sessionId());
            }, exchange -> null).exceptionally(error -> {
                log.log(Level.WARNING, "copilot:events:" + name + " route failed", error);
                return null;
            }).join();
        }
    }

    public String getTypes() {
        return types;
    }

    /** Comma-separated event types to route, with {@code *} wildcards, such as {@code assistant.*,tool.*}. */
    public void setTypes(String types) {
        this.types = types;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }
}
