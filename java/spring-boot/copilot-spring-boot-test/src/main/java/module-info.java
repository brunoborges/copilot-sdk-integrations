/**
 * Test support for applications that use Copilot agents.
 */
@SuppressWarnings({ "requires-transitive-automatic", "requires-automatic" })
module io.github.brunoborges.copilot.spring.boot.test {
    requires transitive io.github.brunoborges.copilot.spring.boot.autoconfigure;
    requires transitive spring.test;
    requires transitive org.mockito;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires static spring.boot.testcontainers;
    requires static testcontainers;

    exports io.github.brunoborges.copilot.test;
    exports io.github.brunoborges.copilot.test.testcontainers;

    opens io.github.brunoborges.copilot.test to com.fasterxml.jackson.databind, spring.beans, spring.boot,
            spring.context, spring.core, spring.test;
    opens io.github.brunoborges.copilot.test.testcontainers to spring.beans, spring.boot, spring.boot.testcontainers,
            spring.context, spring.core;
}
