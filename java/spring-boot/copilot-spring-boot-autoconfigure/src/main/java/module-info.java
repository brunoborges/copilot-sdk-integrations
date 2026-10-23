/**
 * Spring Boot auto-configuration for the Copilot SDK for Java.
 *
 * <p>The agent API ({@code com.microsoft.copilot.integrations.core}) comes from the shared
 * {@code com.microsoft.copilot.integrations.core} module. The {@code autoconfigure}, {@code actuate}, and {@code security}
 * packages are API. {@code com.microsoft.copilot.integrations.springboot.autoconfigure.internal} is not exported; it is opened only
 * to the Spring modules that instantiate its beans reflectively.
 */
@SuppressWarnings("requires-transitive-automatic")
module com.microsoft.copilot.integrations.springboot.autoconfigure {
    requires transitive com.github.copilot.java;
    requires transitive com.microsoft.copilot.integrations.core;
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

    exports com.microsoft.copilot.integrations.springboot.actuate;
    exports com.microsoft.copilot.integrations.springboot.autoconfigure;
    exports com.microsoft.copilot.integrations.springboot.security;

    opens com.microsoft.copilot.integrations.springboot.actuate to spring.beans, spring.boot.actuator, spring.context, spring.core;
    opens com.microsoft.copilot.integrations.springboot.autoconfigure.internal to spring.aop, spring.beans, spring.context, spring.core;
    opens com.microsoft.copilot.integrations.springboot.autoconfigure to spring.beans, spring.boot, spring.context, spring.core;
    opens com.microsoft.copilot.integrations.springboot.security to spring.beans, spring.context, spring.core;
}
