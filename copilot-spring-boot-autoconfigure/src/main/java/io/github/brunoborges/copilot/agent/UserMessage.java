package io.github.brunoborges.copilot.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the non-blank {@code String} parameter sent verbatim as the prompt of a {@link CopilotAgent} method.
 * Every agent method declares exactly one.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface UserMessage {
}
