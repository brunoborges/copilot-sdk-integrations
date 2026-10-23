package com.microsoft.copilot.integrations.micronaut.example;

import java.util.concurrent.CompletionStage;

import com.github.copilot.generated.AssistantMessageEvent;

import com.microsoft.copilot.integrations.core.CopilotAgentEvent;
import com.microsoft.copilot.integrations.core.CopilotAgents;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import reactor.core.publisher.Flux;

@Controller("/advisor")
public class AdvisorController {

    private final MicronautAdvisor advisor;
    private final StreamingAdvisor streaming;
    private final CopilotAgents agents;

    public AdvisorController(MicronautAdvisor advisor, StreamingAdvisor streaming, CopilotAgents agents) {
        this.advisor = advisor;
        this.streaming = streaming;
        this.agents = agents;
    }

    @Post(value = "/once", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public String once(@Body String question) {
        try (var instance = agents.create(BoundAdvisor.class)) {
            return instance.agent().answer(question).join();
        }
    }

    @Post(value = "/{conversation}", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
    public CompletionStage<String> ask(String conversation, @Body String question) {
        return advisor.answer(conversation, question);
    }

    @Post(value = "/{conversation}/stream", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<String> stream(String conversation, @Body String question) {
        return streaming.stream(conversation, question)
                .map(CopilotAgentEvent::event)
                .filter(AssistantMessageEvent.class::isInstance)
                .map(event -> ((AssistantMessageEvent) event).getData().content());
    }
}
