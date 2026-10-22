/**
 * Spring Boot auto-configuration for the Copilot SDK for Java.
 *
 * <p>Only the {@code agent}, {@code autoconfigure}, {@code actuate}, and {@code security} packages are API.
 * {@code io.github.brunoborges.copilot.agent.internal} is not exported; it is opened only to the Spring
 * modules that instantiate its beans reflectively.
 */
@SuppressWarnings("requires-transitive-automatic")
module io.github.brunoborges.copilot.spring.boot.autoconfigure {
    requires transitive com.github.copilot.java;
    requires transitive spring.aop;
    requires transitive spring.beans;
    requires transitive spring.boot;
    requires transitive spring.boot.autoconfigure;
    requires transitive spring.context;
    requires transitive spring.core;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires java.logging;

    requires static context.propagation;
    requires static micrometer.core;
    requires static micrometer.observation;
    requires static spring.boot.actuator;
    requires static spring.boot.actuator.autoconfigure;
    requires static spring.boot.health;
    requires static spring.security.core;
    requires static spring.security.oauth2.client;
    requires static spring.security.oauth2.core;

    exports io.github.brunoborges.copilot.actuate;
    exports io.github.brunoborges.copilot.agent;
    exports io.github.brunoborges.copilot.autoconfigure;
    exports io.github.brunoborges.copilot.security;

    opens io.github.brunoborges.copilot.actuate to spring.beans, spring.boot.actuator, spring.context, spring.core;
    opens io.github.brunoborges.copilot.agent.internal to spring.aop, spring.beans, spring.context, spring.core;
    opens io.github.brunoborges.copilot.autoconfigure to spring.beans, spring.boot, spring.context, spring.core;
    opens io.github.brunoborges.copilot.security to spring.beans, spring.context, spring.core;
}
