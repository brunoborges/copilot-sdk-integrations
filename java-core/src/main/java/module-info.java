/**
 * Framework-neutral declarative agent API and engine for the GitHub Copilot SDK for Java.
 *
 * <p>{@code io.github.brunoborges.copilot.agent} is the public API shared by the Spring Boot and Quarkus
 * integrations. {@code io.github.brunoborges.copilot.agent.internal} is the engine those integrations adapt; it is
 * exported only to them and is not part of the public API.
 */
@SuppressWarnings({"requires-transitive-automatic", "module"})
module io.github.brunoborges.copilot.agent {
    requires transitive com.github.copilot.java;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires java.logging;

    exports io.github.brunoborges.copilot.agent;
    exports io.github.brunoborges.copilot.agent.internal to io.github.brunoborges.copilot.spring.boot.autoconfigure;

    opens io.github.brunoborges.copilot.agent.internal to spring.aop, spring.beans, spring.context, spring.core;
}
