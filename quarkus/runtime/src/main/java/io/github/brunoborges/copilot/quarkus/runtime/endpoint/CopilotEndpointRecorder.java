package io.github.brunoborges.copilot.quarkus.runtime.endpoint;

import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** Kept apart from the core recorder so Vert.x Web is only required when the endpoint is enabled. */
@Recorder
public class CopilotEndpointRecorder {

    public Handler<RoutingContext> handler() {
        return new CopilotEndpointHandler();
    }
}
