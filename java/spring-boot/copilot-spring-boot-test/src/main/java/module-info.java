/**
 * Test support for applications that use Copilot agents.
 */
@SuppressWarnings({ "requires-transitive-automatic", "requires-automatic" })
module com.microsoft.copilot.integrations.springboot.test {
    requires transitive com.microsoft.copilot.integrations.springboot.autoconfigure;
    requires transitive spring.test;
    requires transitive org.mockito;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires static spring.boot.testcontainers;
    requires static testcontainers;

    exports com.microsoft.copilot.integrations.springboot.test;
    exports com.microsoft.copilot.integrations.springboot.test.testcontainers;

    opens com.microsoft.copilot.integrations.springboot.test to com.fasterxml.jackson.databind, spring.beans, spring.boot,
            spring.context, spring.core, spring.test;
    opens com.microsoft.copilot.integrations.springboot.test.testcontainers to spring.beans, spring.boot, spring.boot.testcontainers,
            spring.context, spring.core;
}
