package io.github.brunoborges.copilot.example;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MultiSessionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MultiSessionRunner.class);

    private final ConversationService conversations;
    private final SpringAdvisor advisor;

    public MultiSessionRunner(ConversationService conversations, SpringAdvisor advisor) {
        this.conversations = conversations;
        this.advisor = advisor;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("help")) {
            log.info("Usage: java -jar copilot-spring-boot-example-0.1.0-SNAPSHOT.jar "
                    + "[--copilot.cli-path=/path/to/copilot] [--agent] [\"first prompt\" \"second prompt\"]");
            return;
        }

        var prompts = args.getNonOptionArgs();
        if (prompts.isEmpty()) {
            prompts = List.of(
                    "Explain dependency injection in two sentences. Do not use tools.",
                    "Explain virtual threads in two sentences. Do not use tools.");
        }
        if (prompts.size() != 2) {
            throw new IllegalArgumentException("Supply either no prompts or exactly two quoted prompts; use --help for usage.");
        }

        String firstPrompt = prompts.getFirst();
        String secondPrompt = prompts.getLast();
        if (args.containsOption("agent")) {
            String runId = UUID.randomUUID().toString();
            String firstConversation = runId + "/conversation-1";
            var first = advisor.answer(firstConversation, firstPrompt);
            var second = advisor.answer(runId + "/conversation-2", secondPrompt);
            var followUp = first.thenCompose(answer -> advisor.answer(firstConversation,
                    "In one sentence, summarize the explanation you just gave. Do not use tools."));
            CompletableFuture.allOf(followUp, second).join();
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.<Callable<Void>>of(
                    () -> {
                        conversations.converse("conversation-1", firstPrompt);
                        return null;
                    },
                    () -> {
                        conversations.converse("conversation-2", secondPrompt);
                        return null;
                    }));
            for (var result : results) {
                result.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }
}
