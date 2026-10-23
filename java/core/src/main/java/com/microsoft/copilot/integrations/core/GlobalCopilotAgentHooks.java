package com.microsoft.copilot.integrations.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link CopilotAgentHooks} bean, on its class or {@code @Bean} method, as applying to every
 * {@link CopilotAgent}. Global hooks run before agent-specific hooks, ordered by {@code @Order}.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalCopilotAgentHooks {
}
