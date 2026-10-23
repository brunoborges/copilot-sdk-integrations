package com.microsoft.copilot.integrations.springboot.test.app;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.microsoft.copilot.integrations.core.CopilotAgentInvocation;
import com.microsoft.copilot.integrations.core.CopilotAgentReturnTypeAdapter;

@SpringBootApplication
public class AdvisorApplication {

    @Bean
    CopilotAgentReturnTypeAdapter transcriptAdapter() {
        return new CopilotAgentReturnTypeAdapter() {
            @Override
            public boolean supports(Type returnType) {
                return returnType == Transcript.class;
            }

            @Override
            public Object adapt(CopilotAgentInvocation invocation) {
                var events = Collections.synchronizedList(new ArrayList<com.github.copilot.generated.SessionEvent>());
                Object reply = invocation.invoke(event -> events.add(event.event())).join();
                return new Transcript(events, (String) reply);
            }
        };
    }
}
