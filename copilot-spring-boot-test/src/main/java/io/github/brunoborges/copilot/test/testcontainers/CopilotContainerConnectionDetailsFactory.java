package io.github.brunoborges.copilot.test.testcontainers;

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory;
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource;

import io.github.brunoborges.copilot.autoconfigure.CopilotConnectionDetails;

/**
 * Creates {@link CopilotConnectionDetails} for {@code @ServiceConnection}-annotated {@link CopilotContainer}s.
 */
class CopilotContainerConnectionDetailsFactory
        extends ContainerConnectionDetailsFactory<CopilotContainer, CopilotConnectionDetails> {

    @Override
    protected CopilotConnectionDetails getContainerConnectionDetails(ContainerConnectionSource<CopilotContainer> source) {
        return new CopilotContainerConnectionDetails(source);
    }

    static final class CopilotContainerConnectionDetails extends ContainerConnectionDetails<CopilotContainer>
            implements CopilotConnectionDetails {

        CopilotContainerConnectionDetails(ContainerConnectionSource<CopilotContainer> source) {
            super(source);
        }

        @Override
        public String cliUrl() {
            return getContainer().getCliUrl();
        }

        @Override
        public String tcpConnectionToken() {
            return getContainer().getTcpConnectionToken();
        }
    }
}
