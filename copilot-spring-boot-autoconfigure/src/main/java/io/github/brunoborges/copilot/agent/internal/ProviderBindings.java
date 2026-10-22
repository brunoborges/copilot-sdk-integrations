package io.github.brunoborges.copilot.agent.internal;

import java.util.Map;

import com.github.copilot.rpc.ProviderConfig;
import io.github.brunoborges.copilot.autoconfigure.CopilotProviderProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.util.Assert;

final class ProviderBindings {

    private ProviderBindings() {
    }

    /** Resolves a provider name to a property definition or SDK bean; an empty name selects none. */
    static ProviderConfig bind(String name, BeanFactory beans) {
        if (name.isEmpty()) {
            return null;
        }
        var properties = beans.getBeanProvider(CopilotProviderProperties.class)
                .getIfAvailable(() -> new CopilotProviderProperties(Map.of()));
        var configured = properties.providers().get(name);
        boolean nativeBean = beans.containsBean(name) && beans.isTypeMatch(name, ProviderConfig.class);
        Assert.isTrue(configured == null || !nativeBean,
                "Ambiguous provider definition in properties and SDK bean: " + name);
        Assert.isTrue(configured != null || nativeBean, "Unknown provider reference: " + name);
        return configured == null ? beans.getBean(name, ProviderConfig.class) : configured.toProviderConfig();
    }
}
