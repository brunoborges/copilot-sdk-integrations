package io.github.brunoborges.copilot.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the prompt of a {@link CopilotAgent} method, in one of two mutually exclusive forms.
 * <ul>
 * <li>On a parameter (without attributes): the non-blank {@code String} argument is sent verbatim as the prompt.</li>
 * <li>On the method: a prompt template given inline ({@link #value()}) or as a UTF-8 resource
 * ({@link #resource()}). {@code {{name}}} placeholders are replaced by the {@link P}-annotated arguments of the
 * same name, rendered with {@code String.valueOf}. Configuration property placeholders ({@code ${...}}) in the inline
 * template and the resource location are resolved at startup.</li>
 * </ul>
 * Every agent method declares exactly one of these forms.
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UserMessage {

    /** Inline prompt template; only valid on methods. Mutually exclusive with {@link #resource()}. */
    String value() default "";

    /** Resource location, such as {@code classpath:/prompts/review.txt}, of a UTF-8 prompt template; only valid on methods. */
    String resource() default "";
}
