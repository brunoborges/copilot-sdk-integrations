package io.github.brunoborges.copilot.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.brunoborges.copilot.autoconfigure.CopilotAgentsRegistrar;
import org.springframework.context.annotation.Import;

/**
 * Scans additional packages for {@link CopilotAgent} interfaces outside Spring Boot's auto-configuration
 * packages. Without attributes, the annotated class's package is scanned.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(CopilotAgentsRegistrar.class)
public @interface EnableCopilotAgents {

    /** Base packages to scan; placeholders are supported. */
    String[] basePackages() default {};

    /** Type-safe alternative to {@link #basePackages()}: the package of each class is scanned. */
    Class<?>[] basePackageClasses() default {};
}
