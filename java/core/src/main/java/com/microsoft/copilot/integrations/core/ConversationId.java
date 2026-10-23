package com.microsoft.copilot.integrations.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the non-blank {@code String} parameter that identifies the application conversation of a routed
 * {@link CopilotAgent} method. Calls with the same agent and key share persisted history and are serialized
 * within the application context. The key is not an authorization mechanism; scope it to the authorized
 * user or tenant.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConversationId {
}
