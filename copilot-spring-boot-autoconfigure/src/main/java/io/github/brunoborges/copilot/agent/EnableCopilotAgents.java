package io.github.brunoborges.copilot.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsRegistrar;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(CopilotAgentsRegistrar.class)
public @interface EnableCopilotAgents {
    String[] basePackages() default {};
    Class<?>[] basePackageClasses() default {};
}
