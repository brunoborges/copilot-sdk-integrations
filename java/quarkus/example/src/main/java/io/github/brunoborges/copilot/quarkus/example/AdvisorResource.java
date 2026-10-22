package io.github.brunoborges.copilot.quarkus.example;

import java.util.concurrent.CompletionStage;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.RestStreamElementType;

import com.github.copilot.generated.AssistantMessageEvent;

import io.github.brunoborges.copilot.agent.CopilotAgentEvent;
import io.github.brunoborges.copilot.agent.CopilotAgents;
import io.smallrye.mutiny.Multi;

@Path("/advisor")
@Consumes(MediaType.TEXT_PLAIN)
public class AdvisorResource {

    private final QuarkusAdvisor advisor;
    private final StreamingAdvisor streaming;
    private final CopilotAgents agents;

    public AdvisorResource(QuarkusAdvisor advisor, StreamingAdvisor streaming, CopilotAgents agents) {
        this.advisor = advisor;
        this.streaming = streaming;
        this.agents = agents;
    }

    @POST
    @Path("/{conversation}")
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> ask(@PathParam("conversation") String conversation, String question) {
        return advisor.answer(conversation, question);
    }

    @POST
    @Path("/once")
    @Produces(MediaType.TEXT_PLAIN)
    public String once(String question) {
        try (var instance = agents.create(BoundAdvisor.class)) {
            return instance.agent().answer(question).join();
        }
    }

    @POST
    @Path("/{conversation}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream(@PathParam("conversation") String conversation, String question) {
        return streaming.stream(conversation, question)
                .map(CopilotAgentEvent::event)
                .filter(AssistantMessageEvent.class::isInstance)
                .map(event -> ((AssistantMessageEvent) event).getData().content());
    }
}
