/**
 * Framework-neutral declarative agent API and engine for the GitHub Copilot SDK for Java.
 *
 * <p>{@code com.microsoft.copilot.integrations.core} is the public API shared by the Spring Boot, Quarkus, and
 * Micronaut integrations. {@code com.microsoft.copilot.integrations.core.internal} is the engine those integrations
 * adapt; it is not part of the public API.
 */
@SuppressWarnings({"requires-transitive-automatic", "module"})
module com.microsoft.copilot.integrations.core {
    requires transitive com.github.copilot.java;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires java.logging;

    exports com.microsoft.copilot.integrations.core;
    exports com.microsoft.copilot.integrations.core.internal to com.microsoft.copilot.integrations.springboot.autoconfigure;

    opens com.microsoft.copilot.integrations.core.internal to spring.aop, spring.beans, spring.context, spring.core;
}
