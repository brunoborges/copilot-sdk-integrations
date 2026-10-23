package io.github.brunoborges.copilot.camel;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultAsyncProducer;

import com.github.copilot.rpc.MessageSource;

import io.github.brunoborges.copilot.agent.CopilotMessageSources;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;

/** Sends the prompt to the agent without blocking the route thread and sets the reply as the body. */
final class CopilotAgentProducer extends DefaultAsyncProducer {

    private static final Logger log = Logger.getLogger(CopilotAgentProducer.class.getName());

    private static final Set<String> SCHEDULING_SCHEMES = Set.of("timer", "scheduler", "quartz", "cron");

    private final CopilotAgentEndpoint endpoint;

    CopilotAgentProducer(CopilotAgentEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        CompletableFuture<?> reply;
        CopilotAgentDefinition definition;
        String conversationId;
        boolean oneShot;
        try {
            definition = endpoint.definition();
            String prompt = endpoint.prompt(exchange);
            conversationId = endpoint.conversationId(exchange);
            oneShot = conversationId == null;
            if (oneShot) {
                conversationId = UUID.randomUUID().toString();
            }
            var invoker = endpoint.getComponent().invoker();
            var structured = endpoint.structured();
            String id = conversationId;
            Supplier<CompletableFuture<?>> send = () -> structured == null ? invoker.invoke(definition, id, prompt)
                    : structured.convert(invoker.invokeStructured(definition, id, prompt, structured.schema(), null));
            reply = scheduled(exchange, definition) ? CopilotMessageSources.callAs(MessageSource.SYSTEM, send)
                    : send.get();
        } catch (RuntimeException ex) {
            exchange.setException(ex);
            callback.done(true);
            return true;
        }
        String conversation = conversationId;
        reply.whenComplete((result, error) -> {
            try {
                if (error != null) {
                    exchange.setException(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                } else {
                    var message = exchange.getMessage();
                    message.setBody(result);
                    message.setHeader(CopilotConstants.AGENT_NAME, definition.name());
                    if (oneShot) {
                        message.removeHeader(CopilotConstants.CONVERSATION_ID);
                    } else {
                        message.setHeader(CopilotConstants.CONVERSATION_ID, conversation);
                    }
                }
                if (oneShot) {
                    discard(definition, conversation);
                }
            } finally {
                callback.done(false);
            }
        });
        return false;
    }

    /**
     * Whether the exchange was started by a scheduling consumer and the agent declares no source of its own, in
     * which case the prompt is application-generated rather than user input.
     */
    private static boolean scheduled(Exchange exchange, CopilotAgentDefinition definition) {
        if (definition.messageSource() != null || CopilotMessageSources.current() != null) {
            return false;
        }
        var from = exchange.getFromEndpoint();
        if (from == null) {
            return false;
        }
        String uri = from.getEndpointUri();
        int colon = uri.indexOf(':');
        return colon > 0 && SCHEDULING_SCHEMES.contains(uri.substring(0, colon));
    }

    /** One-shot conversations cannot be continued, so their sessions are deleted rather than left behind. */
    private void discard(CopilotAgentDefinition definition, String conversationId) {
        var client = endpoint.getComponent().activeClient();
        if (client == null || definition.tokenResolver() != null) {
            return;
        }
        try {
            client.deleteSession(CopilotAgentInvoker.sessionId(definition, conversationId)).exceptionally(error -> {
                log.log(Level.FINE, "Could not delete one-shot Copilot session", error);
                return null;
            });
        } catch (RuntimeException ex) {
            log.log(Level.FINE, "Could not delete one-shot Copilot session", ex);
        }
    }
}
