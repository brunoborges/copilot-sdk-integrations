package io.github.brunoborges.copilot.reactor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

/**
 * @param bufferCapacity maximum SDK events buffered per stream subscription before failing
 */
@ConfigurationProperties("copilot.reactor")
public record CopilotReactorProperties(@DefaultValue("256") int bufferCapacity) {
    public CopilotReactorProperties {
        Assert.isTrue(bufferCapacity > 0, "copilot.reactor.buffer-capacity must be positive");
    }
}
