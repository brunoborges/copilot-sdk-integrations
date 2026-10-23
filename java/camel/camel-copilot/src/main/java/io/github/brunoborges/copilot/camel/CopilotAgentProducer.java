package io.github.brunoborges.copilot.camel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultAsyncProducer;

import io.github.brunoborges.copilot.agent.internal.CopilotAgentDefinition;
import io.github.brunoborges.copilot.agent.internal.CopilotAgentInvoker;

/** Sends the prompt to the agent without blocking the route thread and sets the reply as the body. */
final class CopilotAgentProducer extends DefaultAsyncProducer {

    private static final Logger log = Logger.getLogger(CopilotAgentProducer.class.getName());

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
            reply = structured == null ? invoker.invoke(definition, conversationId, prompt)
                    : structured.convert(invoker.invokeStructured(definition, conversationId, prompt,
                            structured.schema(), null));
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
