/**
 * Project Reactor adapters for Copilot agents.
 */
@SuppressWarnings("requires-transitive-automatic")
module com.microsoft.copilot.integrations.springboot.reactor {
    requires transitive com.microsoft.copilot.integrations.springboot.autoconfigure;
    requires transitive reactor.core;
    requires transitive org.reactivestreams;

    exports com.microsoft.copilot.integrations.springboot.reactor;

    opens com.microsoft.copilot.integrations.springboot.reactor to spring.beans, spring.boot, spring.context, spring.core;
}
