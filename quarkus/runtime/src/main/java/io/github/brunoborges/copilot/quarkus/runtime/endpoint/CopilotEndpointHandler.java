package io.github.brunoborges.copilot.quarkus.runtime.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** Serves {@link CopilotEndpoint} views as JSON; registered as a blocking non-application route. */
public class CopilotEndpointHandler implements Handler<RoutingContext> {

    @Override
    public void handle(RoutingContext context) {
        var endpoint = Arc.container().instance(CopilotEndpoint.class).get();
        String section = context.pathParam("section");
        Object body = section == null || section.isEmpty() ? endpoint.copilot() : endpoint.section(section);
        if (body == null) {
            context.response().setStatusCode(404).end();
            return;
        }
        var mapper = Arc.container().instance(ObjectMapper.class);
        try {
            String json = (mapper.isAvailable() ? mapper.get() : new ObjectMapper()).writeValueAsString(body);
            context.response().putHeader("Content-Type", "application/json").end(json);
        } catch (JsonProcessingException ex) {
            context.fail(ex);
        }
    }
}
