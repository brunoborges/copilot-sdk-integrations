package io.github.brunoborges.copilot.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.github.copilot.SystemMessageMode;

/** Declares a singleton agent proxy; conversation state is owned by the SDK, not the proxy. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CopilotAgent {
    String name() default "";
    String instructions() default "";
    String instructionsResource() default "";
    SystemMessageMode instructionsMode() default SystemMessageMode.APPEND;
    /** Named SystemMessageConfig bean, mutually exclusive with the instruction attributes. */
    String systemMessage() default "";
    String model() default "";
    String reasoningEffort() default "";
    String permissionHandler() default "";
    String[] tools() default {};
    String[] listeners() default {};
    String[] customizers() default {};
}
