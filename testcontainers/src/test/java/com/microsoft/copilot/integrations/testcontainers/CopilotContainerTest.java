package com.microsoft.copilot.integrations.testcontainers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CopilotContainerTest {

    @Test
    void configuresDefaultsAndIndependentSecrets() {
        try (var first = new CopilotContainer("copilot-runtime:test");
             var second = new CopilotContainer("copilot-runtime:test")) {
            first.configure();
            assertEquals(4321, first.getCopilotPort());
            assertEquals("4321", first.getEnvMap().get("COPILOT_PORT"));
            assertEquals(first.getTcpConnectionToken(), first.getEnvMap().get("COPILOT_CONNECTION_TOKEN"));
            assertNotEquals(first.getTcpConnectionToken(), second.getTcpConnectionToken());
            assertFalse(first.getEnvMap().containsKey("COPILOT_GITHUB_TOKEN"));
            assertEquals(java.util.List.of(4321), first.getExposedPorts());
        }
    }

    @Test
    void configuresCustomPortAndExplicitCredentials() {
        try (var container = new CopilotContainer("copilot-runtime:test")
                .withCopilotPort(5432).withConnectionToken("connection-secret").withGitHubToken("test-token")) {
            container.configure();
            assertEquals(java.util.List.of(5432), container.getExposedPorts());
            assertEquals("5432", container.getEnvMap().get("COPILOT_PORT"));
            assertEquals("connection-secret", container.getEnvMap().get("COPILOT_CONNECTION_TOKEN"));
            assertEquals("test-token", container.getEnvMap().get("COPILOT_GITHUB_TOKEN"));
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        try (var container = new CopilotContainer("copilot-runtime:test")) {
            for (int port : new int[]{-1, 0, 1023, 65536}) {
                assertThrows(IllegalArgumentException.class, () -> container.withCopilotPort(port));
            }
            assertThrows(IllegalArgumentException.class, () -> container.withConnectionToken(null));
            assertThrows(IllegalArgumentException.class, () -> container.withConnectionToken(" "));
            assertThrows(IllegalArgumentException.class, () -> container.withGitHubToken(null));
            assertThrows(IllegalArgumentException.class, () -> container.withGitHubToken(""));
        }
    }

    @Test
    void formatsMappedAddressesIncludingIpv6() {
        try (var container = new CopilotContainer("copilot-runtime:test") {
            @Override public String getHost() { return "::1"; }
            @Override public Integer getMappedPort(int port) {
                assertEquals(4321, port);
                return 49152;
            }
        }) {
            assertEquals("[::1]:49152", container.getEndpoint());
            assertEquals("http://[::1]:49152", container.getCliUrl());
        }
    }
}
