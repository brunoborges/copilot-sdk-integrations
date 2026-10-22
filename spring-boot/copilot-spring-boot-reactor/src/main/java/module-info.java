/**
 * Project Reactor adapters for Copilot agents.
 */
@SuppressWarnings("requires-transitive-automatic")
module io.github.brunoborges.copilot.spring.boot.reactor {
    requires transitive io.github.brunoborges.copilot.spring.boot.autoconfigure;
    requires transitive reactor.core;
    requires transitive org.reactivestreams;

    exports io.github.brunoborges.copilot.reactor;

    opens io.github.brunoborges.copilot.reactor to spring.beans, spring.boot, spring.context, spring.core;
}
