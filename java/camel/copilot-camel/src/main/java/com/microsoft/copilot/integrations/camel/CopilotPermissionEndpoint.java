package com.microsoft.copilot.integrations.camel;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.camel.Consumer;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;

import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResult;

/**
 * {@code copilot:permission:<name>} – decides permission requests in a route. The endpoint registers a
 * {@link PermissionHandler} bean called {@code <name>} that agents select with {@code permissionHandlerRef}.
 *
 * <p>The body is the {@link PermissionRequest}. The route approves by ending with {@code true}, {@code "approve"}
 * or an approving {@link PermissionRequestResult}; any other body, a failure, a timeout or a stopped route
 * rejects the request.
 */
public class CopilotPermissionEndpoint extends DefaultEndpoint {

    private static final Logger log = Logger.getLogger(CopilotPermissionEndpoint.class.getName());

    private final String name;
    private Duration timeout = Duration.ofSeconds(30);
    private volatile DefaultConsumer consumer;

    CopilotPermissionEndpoint(String uri, CopilotComponent component, String name) {
        super(uri, component);
        this.name = name;
    }

    @Override
    protected void doInit() throws Exception {
        super.doInit();
        getCamelContext().getRegistry().bind(name, PermissionHandler.class, (PermissionHandler) this::handle);
    }

    private CompletableFuture<PermissionRequestResult> handle(PermissionRequest request, PermissionInvocation invocation) {
        var current = consumer;
        if (current == null) {
            return CompletableFuture.completedFuture(reject(request, "the permission route is not running"));
        }
        return RouteDispatch.dispatch(current, exchange -> {
            var message = exchange.getMessage();
            message.setBody(request);
            message.setHeader(CopilotConstants.PERMISSION_KIND, request.getKind());
            message.setHeader(CopilotConstants.TOOL_CALL_ID, request.getToolCallId());
            message.setHeader(CopilotConstants.SESSION_ID, invocation == null ? null : invocation.getSessionId());
        }, exchange -> decision(request, exchange))
                .completeOnTimeout(null, timeout.toNanos(), TimeUnit.NANOSECONDS)
                .handle((result, error) -> {
                    if (error != null) {
                        log.log(Level.WARNING, "copilot:permission:" + name + " failed; rejecting the request", error);
                        return reject(request, "the permission route failed");
                    }
                    return result != null ? result : reject(request, "the permission route timed out");
                });
    }

    private PermissionRequestResult decision(PermissionRequest request, Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        if (body instanceof PermissionRequestResult result) {
            return result;
        }
        if (body instanceof Boolean approved) {
            return approved ? PermissionRequestResult.approveOnce() : reject(request, "denied by the permission route");
        }
        if (body instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "approve", "approved", "allow", "true" -> PermissionRequestResult.approveOnce();
                default -> reject(request, "denied by the permission route");
            };
        }
        return reject(request, "the permission route did not approve it");
    }

    private PermissionRequestResult reject(PermissionRequest request, String reason) {
        log.warning("copilot:permission:" + name + " rejected a permission request of kind '" + request.getKind()
                + "' (" + reason + ").");
        return PermissionRequestResult.reject("Permission request of kind '" + request.getKind()
                + "' was not approved.");
    }

    @Override
    public Producer createProducer() {
        throw new UnsupportedOperationException("copilot:permission is a consumer; agents use it via permissionHandlerRef");
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        var created = new DefaultConsumer(this, processor) {
            @Override
            protected void doStart() throws Exception {
                super.doStart();
                if (consumer != null) {
                    throw new IllegalStateException("copilot:permission:" + name + " already has a consumer");
                }
                consumer = this;
            }

            @Override
            protected void doStop() throws Exception {
                if (consumer == this) {
                    consumer = null;
                }
                super.doStop();
            }
        };
        configureConsumer(created);
        return created;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /** How long to wait for the route's decision before rejecting. Defaults to 30 seconds. */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
