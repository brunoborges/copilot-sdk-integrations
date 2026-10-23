package com.microsoft.copilot.integrations.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a {@link CopilotAgentHooks} bean, on its class or {@code @Bean} method, to the listed
 * {@link CopilotAgent} interfaces. Targeted hooks run after {@link GlobalCopilotAgentHooks} beans and before the
 * agent's {@link CopilotAgent#hookRefs()}, ordered by {@code @Order}.
 *
 * <pre>{@code
 * @Bean
 * @CopilotAgentHooksFor(SupportAgent.class)
 * CopilotAgentHooks supportAudit() { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CopilotAgentHooksFor {

    /** {@link CopilotAgent} interfaces the hooks apply to. */
    Class<?>[] value();
}
