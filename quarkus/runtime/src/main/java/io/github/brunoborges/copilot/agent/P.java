package io.github.brunoborges.copilot.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a {@link CopilotAgent} method parameter to a {@code {{name}}} placeholder of the method-level
 * {@link UserMessage} template. Arguments must be non-null and are rendered with {@code String.valueOf}.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface P {

    /**
     * Placeholder name. Empty uses the parameter name, which requires compiling with {@code -parameters}.
     */
    String value() default "";
}
